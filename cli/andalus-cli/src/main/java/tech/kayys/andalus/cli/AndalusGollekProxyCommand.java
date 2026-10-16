package tech.kayys.andalus.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.lang.reflect.Method;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.concurrent.Callable;

@Command(name = "aqli", description = "Proxy commands to the local aqli CLI.", mixinStandardHelpOptions = true,
        subcommands = { AndalusAqliProxyCommand.ListCommand.class, AndalusAqliProxyCommand.PullCommand.class })
public class AndalusAqliProxyCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("Use subcommands: list, pull");
        return 0;
    }

    static int runProcessAndPipe(PrintStream out, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.println(line);
            }
        }
        int rc = p.waitFor();
        return rc;
    }

    @Command(name = "list", description = "Delegate to 'aqli list' to show available manifests/models.")
    public static class ListCommand implements Callable<Integer> {
        @Option(names = {"--manifest", "-m"}, description = "List manifests instead of models")
        boolean manifests;

        @Override
        public Integer call() throws Exception {
            PrintStream out = System.out;
            try {
                if (manifests) {
                    // prefer SDK if available
                    try {
                        Object sdk = Class.forName("tech.kayys.aqli.factory.AqliSdkFactory").getMethod("createLocalSdk").invoke(null);
                        Method listManifests = sdk.getClass().getMethod("listModels");
                        Object res = listManifests.invoke(sdk);
                        if (res instanceof java.util.List) {
                            for (Object o : (java.util.List<?>) res) out.println(o.toString());
                        }
                        return 0;
                    } catch (Throwable t) {
                        return runProcessAndPipe(out, "aqli", "manifest", "list");
                    }
                } else {
                    try {
                        // Prefer SDK
                        java.util.List<?> models = tech.kayys.andalus.client.AndalusAqliFacade.listModels();
                        if (models == null || models.isEmpty()) {
                            out.println("No models found.");
                            return 0;
                        }
                        for (Object m : models) out.println(m == null ? "null" : m.toString());
                        return 0;
                    } catch (Throwable t) {
                        return runProcessAndPipe(out, "aqli", "list");
                    }
                }
            } catch (IOException | InterruptedException e) {
                out.println("Error: failed to run aqli list: " + e.getMessage());
                return 2;
            }
        }
    }

    @Command(name = "pull", description = "Delegate to 'aqli pull <model>' to download model files.")
    public static class PullCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "Model id to pull")
        String modelId;

        @Option(names = {"--yes", "-y"}, description = "Auto-confirm download")
        boolean yes;

        @Override
        public Integer call() throws Exception {
            PrintStream out = System.out;
            try {
                if (!yes) {
                    // simple confirmation
                    out.print("Download model '" + modelId + "'? [Y/n]: ");
                    out.flush();
                    BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
                    String line = r.readLine();
                    if (line != null && line.trim().length() > 0 && line.trim().toLowerCase().startsWith("n")) {
                        out.println("Aborted.");
                        return 0;
                    }
                }
                // Prefer SDK-backed pull when available
                try {
                    int rc = tech.kayys.andalus.client.AndalusAqliFacade.pullModel(out, modelId);
                    return rc;
                } catch (Throwable t) {
                    return runProcessAndPipe(out, "aqli", "pull", modelId);
                }
            } catch (IOException | InterruptedException e) {
                out.println("Error: failed to run aqli pull: " + e.getMessage());
                return 2;
            }
        }
    }
}
