package tech.kayys.andalus.api.grpc;

import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.andalus.project.ProjectStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class GrpcSdkService extends SdkServiceGrpc.SdkServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(GrpcSdkService.class);
    private final ProjectStore store;

    // Path to built-in provider plugin descriptors (resolved relative to andalus-platform root)
    private static final String[] PROVIDER_PLUGIN_SEARCH_ROOTS = {
        System.getProperty("user.home") + "/Workspace/workkayys/Products/Andalus/andalus-platform/Families/andalus/modules/provider",
        System.getProperty("andalus.modules.provider.dir", "")
    };

    public GrpcSdkService() {
        try {
            this.store = new ProjectStore(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize ProjectStore", e);
        }
    }

    // ── ListProviders ─────────────────────────────────────────────────────────
    // Scans Families/andalus/modules/provider/**/plugin.json for built-in providers,
    // then always appends aqli as the local inference provider.

    @Override
    public void listProviders(Empty req, StreamObserver<ProviderList> resp) {
        try {
            ProviderList.Builder b = ProviderList.newBuilder();

            // 1. Scan from the modules/provider directory
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
                            
                            // Check if it has a plugin.json for detailed info
                            Path pluginJson = dir.resolve("src/main/resources/plugin.json");
                            if (Files.exists(pluginJson)) {
                                try {
                                    String json = Files.readString(pluginJson);
                                    String id = extractJsonString(json, "id");
                                    String name = extractJsonString(json, "name");
                                    String version = extractJsonString(json, "version");
                                    String description = extractJsonString(json, "description");
                                    if (id != null && !id.isBlank()) {
                                        ProviderInfo.Builder pi = ProviderInfo.newBuilder()
                                            .setId(id)
                                            .setName(name != null ? name : id)
                                            .setDescription(description != null ? description : "");
                                        if (version != null) pi.addCapabilities("version:" + version);
                                        List<String> caps = extractJsonArray(json, "capabilities");
                                        caps.forEach(pi::addCapabilities);
                                        b.addProviders(pi.build());
                                        return;
                                    }
                                } catch (Exception ignored) {}
                            }
                            
                            // Fallback to basic info from directory name
                            b.addProviders(ProviderInfo.newBuilder()
                                .setId(providerId)
                                .setName(providerId.substring(0, 1).toUpperCase() + providerId.substring(1))
                                .setDescription("Built-in " + providerId + " provider")
                                .build());
                        });
                }
                break; // Use first valid root
            }

            // 2. Always include aqli as the local inference provider if not already present
            boolean hasAqli = b.getProvidersList().stream().anyMatch(p -> p.getId().contains("aqli"));
            if (!hasAqli) {
                b.addProviders(ProviderInfo.newBuilder()
                    .setId("aqli")
                    .setName("Aqli")
                    .setDescription("Local Aqli inference engine")
                    .addCapabilities("llm")
                    .addCapabilities("streaming")
                    .build());
            }

            resp.onNext(b.build());
            resp.onCompleted();
        } catch (Exception e) {
            log.error("listProviders failed", e);
            resp.onError(io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    // ── ListModels ────────────────────────────────────────────────────────────
    // Uses `aqli list --format json` CLI to get locally available models.

    @Override
    public void listModels(ListModelsRequest req, StreamObserver<ModelList> resp) {
        try {
            ModelList.Builder b = ModelList.newBuilder();

            Process p = new ProcessBuilder("aqli", "list", "--format", "json")
                .redirectErrorStream(true)
                .start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            int exit = p.waitFor();

            if (exit == 0 && sb.length() > 0) {
                // Parse JSON array of model objects
                String json = sb.toString().trim();
                // Each entry: {"id":"...","shortId":"...","name":"...","format":"...","size":...}
                int depth = 0;
                StringBuilder obj = new StringBuilder();
                for (char c : json.toCharArray()) {
                    if (c == '{') { depth++; obj.append(c); }
                    else if (c == '}') {
                        obj.append(c);
                        if (--depth == 0) {
                            String o = obj.toString();
                            String id = extractJsonString(o, "id");
                            String name = extractJsonString(o, "name");
                            String format = extractJsonString(o, "format");
                            String shortId = extractJsonString(o, "shortId");
                            if (id == null || id.isBlank()) { obj.setLength(0); continue; }
                            if (name == null) name = id;
                            String desc = (format != null ? format : "") + (shortId != null ? " [" + shortId + "]" : "");
                            b.addModels(ModelInfo.newBuilder()
                                .setId(id)
                                .setName(name)
                                .setDescription(desc.trim())
                                .build());
                            obj.setLength(0);
                        }
                    } else if (depth > 0) {
                        obj.append(c);
                    }
                }
            } else {
                // Return empty list if aqli CLI is unavailable
                // b.addModels(ModelInfo.newBuilder().setId("gemma-3").setName("Gemma 3").setDescription("gguf").build());
            }

            resp.onNext(b.build());
            resp.onCompleted();
        } catch (Exception e) {
            log.error("listModels failed", e);
            resp.onError(io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void createSdk(CreateSdkRequest req, StreamObserver<CreateSdkResponse> resp) {
        try {
            String provider = req.getProviderId();
            if (provider == null || provider.isBlank()) provider = "aqli";
            String sdkId = "sdk-" + provider + "-local";
            resp.onNext(CreateSdkResponse.newBuilder()
                .setStatus(OperationResponse.newBuilder().setOk(true).setMessage("created").build())
                .setSdkId(sdkId).build());
            resp.onCompleted();
        } catch (Exception e) {
            log.error("createSdk failed", e);
            resp.onError(io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void sdkStream(SdkStreamRequest req, StreamObserver<CodeChunk> resp) {
        try {
            String prompt = req.getPrompt();
            if (prompt == null) prompt = "";
            String[] parts = prompt.split("\\s+");
            int i = 0;
            for (String part : parts) {
                resp.onNext(CodeChunk.newBuilder().setText(part + " ").setSeq(i++).setDone(false).build());
                try { Thread.sleep(30); } catch (InterruptedException ignored) {}
            }
            resp.onNext(CodeChunk.newBuilder().setText("\n").setSeq(i).setDone(true).build());
            resp.onCompleted();
        } catch (Exception e) {
            log.error("sdkStream failed", e);
            resp.onError(io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String extractJsonString(String json, String key) {
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
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ']') end++;
            return json.substring(vs, end).trim();
        }
    }

    private List<String> extractJsonArray(String json, String key) {
        List<String> result = new ArrayList<>();
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx == -1) return result;
        int arrStart = json.indexOf('[', idx);
        int arrEnd = json.indexOf(']', arrStart);
        if (arrStart == -1 || arrEnd == -1) return result;
        String arr = json.substring(arrStart + 1, arrEnd);
        for (String part : arr.split(",")) {
            String v = part.trim().replaceAll("^\"|\"$", "");
            if (!v.isBlank()) result.add(v);
        }
        return result;
    }
}
