package tech.kayys.andalus.tui.provider;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import tech.kayys.andalus.api.grpc.Empty;
import tech.kayys.andalus.api.grpc.ProviderList;
import tech.kayys.andalus.api.grpc.SdkServiceGrpc;
import tech.kayys.andalus.tui.ui.ProviderManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * ProviderManager backed by the Andalus gRPC SdkService.ListProviders RPC.
 * The server scans the built-in modules directory and checks local CLI status.
 */
public class AqliProviderManager implements ProviderManager {

    private final String grpcTarget;

    // Same fallback search roots as the server, in case gRPC is unavailable
    private static final String[] PROVIDER_PLUGIN_SEARCH_ROOTS = {
        System.getProperty("user.home") + "/Workspace/workkayys/Products/Andalus/andalus-platform/Families/andalus/modules/provider",
        System.getProperty("andalus.modules.provider.dir", "")
    };

    public AqliProviderManager(String grpcTarget) {
        String t = grpcTarget != null ? grpcTarget : "localhost:31013";
        if (t.startsWith("http://")) t = t.substring(7);
        if (t.startsWith("https://")) t = t.substring(8);
        int slash = t.indexOf('/');
        if (slash != -1) t = t.substring(0, slash);
        this.grpcTarget = t;
    }

    @Override
    public List<ProviderRow> listProviders() {
        List<ProviderRow> result = new ArrayList<>();
        ManagedChannel channel = null;
        try {
            channel = ManagedChannelBuilder.forTarget(grpcTarget)
                .usePlaintext()
                .build();
            SdkServiceGrpc.SdkServiceBlockingStub stub =
                SdkServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS);

            ProviderList providers = stub.listProviders(Empty.newBuilder().build());
            for (var p : providers.getProvidersList()) {
                result.add(new ProviderRow(p.getId(), p.getName(), "?", "running", ""));
            }
        } catch (Exception e) {
            // Server unavailable: Fallback to scanning locally
            result.addAll(listViaLocalScan());
        } finally {
            if (channel != null) {
                try { channel.shutdownNow().awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }
        return result;
    }

    /** Direct file scan fallback when the gRPC server isn't running. */
    private List<ProviderRow> listViaLocalScan() {
        List<ProviderRow> result = new ArrayList<>();
        for (String root : PROVIDER_PLUGIN_SEARCH_ROOTS) {
            if (root == null || root.isBlank()) continue;
            Path rootPath = Paths.get(root);
            if (!Files.isDirectory(rootPath)) continue;
            try (Stream<Path> walk = Files.walk(rootPath, 2)) {
                walk.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("andalus-plugin-"))
                    .forEach(dir -> {
                        String dirName = dir.getFileName().toString();
                        String providerId = dirName.substring("andalus-plugin-".length());
                        
                        Path pluginJson = dir.resolve("src/main/resources/plugin.json");
                        if (Files.exists(pluginJson)) {
                            try {
                                String json = Files.readString(pluginJson);
                                String id = extract(json, "id");
                                String name = extract(json, "name");
                                if (id != null && !id.isBlank()) {
                                    result.add(new ProviderRow(id, name != null ? name : id, "?", "running", ""));
                                    return;
                                }
                            } catch (Exception ignored) {}
                        }
                        
                        result.add(new ProviderRow(
                            providerId, 
                            providerId.substring(0, 1).toUpperCase() + providerId.substring(1), 
                            "?", "running", ""));
                    });
            } catch (Exception ignored) {}
            break; // Use first valid root
        }
        
        // Always add aqli if not present
        if (result.stream().noneMatch(p -> p.id().contains("aqli"))) {
            result.add(new ProviderRow("aqli", "Aqli", "?", "running", ""));
        }
        return result;
    }

    private String extract(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx == -1) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon == -1) return null;
        int vs = colon + 1;
        while (vs < json.length() && json.charAt(vs) == ' ') vs++;
        if (vs >= json.length()) return null;
        char first = json.charAt(vs);
        if (first == '"') {
            int end = json.indexOf('"', vs + 1);
            return end == -1 ? null : json.substring(vs + 1, end);
        } else if (first == 'n') {
            return null;
        } else {
            int end = vs;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            return json.substring(vs, end).trim();
        }
    }
}
