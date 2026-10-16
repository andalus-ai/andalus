package tech.kayys.andalus.tui.provider;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import tech.kayys.andalus.api.grpc.Empty;
import tech.kayys.andalus.api.grpc.ListModelsRequest;
import tech.kayys.andalus.api.grpc.ModelList;
import tech.kayys.andalus.api.grpc.SdkServiceGrpc;
import tech.kayys.andalus.tui.ui.ModelManager;

import java.io.PrintStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ModelManager backed by the Andalus gRPC SdkService.ListModels RPC.
 * The server in turn calls {@code aqli list --format json} to get locally
 * installed models from the Aqli inference engine.
 */
public class AqliModelManager implements ModelManager {

    private final String grpcTarget;

    public AqliModelManager(String grpcTarget) {
        // Accept "host:port" or a full "http://host:port" URL
        String t = grpcTarget != null ? grpcTarget : "localhost:31013";
        if (t.startsWith("http://")) t = t.substring(7);
        if (t.startsWith("https://")) t = t.substring(8);
        // Strip trailing slash or path
        int slash = t.indexOf('/');
        if (slash != -1) t = t.substring(0, slash);
        this.grpcTarget = t;
    }

    @Override
    public List<ModelRow> listModels() {
        List<ModelRow> result = new ArrayList<>();
        ManagedChannel channel = null;
        try {
            channel = ManagedChannelBuilder.forTarget(grpcTarget)
                .usePlaintext()
                .build();
            SdkServiceGrpc.SdkServiceBlockingStub stub =
                SdkServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS);

            ModelList models = stub.listModels(ListModelsRequest.newBuilder().build());
            for (var m : models.getModelsList()) {
                // description field carries "format [shortId]" encoded by GrpcSdkService
                String desc = m.getDescription();
                String format = "gguf";
                String size = "";
                if (desc != null && !desc.isBlank()) {
                    // Parse "gguf [bc0b85]" → format="gguf"
                    int bracket = desc.indexOf('[');
                    if (bracket > 0) {
                        format = desc.substring(0, bracket).trim();
                        size = desc.substring(bracket + 1, desc.length() - 1).trim();
                    } else {
                        format = desc.trim();
                    }
                }
                result.add(new ModelRow(m.getId(), m.getName(), format, size));
            }
        } catch (Exception e) {
            // gRPC unavailable: fall back to aqli CLI directly
            result.addAll(listViaAqliCli());
        } finally {
            if (channel != null) {
                try { channel.shutdownNow().awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }
        return result;
    }

    /** Direct CLI fallback when the gRPC server isn't running. */
    private List<ModelRow> listViaAqliCli() {
        List<ModelRow> result = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("aqli", "list", "--format", "json")
                .redirectErrorStream(true)
                .start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            if (p.waitFor() != 0 || sb.length() == 0) return result;

            String json = sb.toString().trim();
            int depth = 0;
            StringBuilder obj = new StringBuilder();
            for (char c : json.toCharArray()) {
                if (c == '{') { depth++; obj.append(c); }
                else if (c == '}') {
                    obj.append(c);
                    if (--depth == 0) {
                        String o = obj.toString();
                        String id = extract(o, "id");
                        String name = extract(o, "name");
                        String format = extract(o, "format");
                        String shortId = extract(o, "shortId");
                        if (id != null && !id.isBlank()) {
                            result.add(new ModelRow(
                                id,
                                name != null ? name : id,
                                format != null ? format : "gguf",
                                shortId != null ? shortId : ""
                            ));
                        }
                        obj.setLength(0);
                    }
                } else if (depth > 0) {
                    obj.append(c);
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    @Override
    public int pullModel(PrintStream out, String modelSpec) {
        out.println("Pulling model: " + modelSpec);
        try {
            Process p = new ProcessBuilder("aqli", "pull", modelSpec)
                .redirectErrorStream(true)
                .start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) out.println(line);
            }
            return p.waitFor();
        } catch (Exception e) {
            out.println("Failed to pull model: " + e.getMessage());
            return 1;
        }
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
