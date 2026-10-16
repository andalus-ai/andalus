package tech.kayys.andalus.cli;

import tech.kayys.andalus.client.AndalusAqliFacade;

import java.io.PrintStream;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Lightweight adapter that provides a stable runtime surface for the CLI
 * without requiring the full Aqli SDK classes on the classpath.
 *
 * It prefers the local Aqli SDK when available (via AndalusAqliFacade reflection),
 * otherwise falls back to shell-based operations implemented in AndalusAqliFacade.
 */
public final class AqliSdkAdapter {

    private String preferredProvider;

    public AqliSdkAdapter() {}

    public static AqliSdkAdapter create(String preferredProvider) {
        AqliSdkAdapter a = new AqliSdkAdapter();
        a.preferredProvider = preferredProvider;
        return a;
    }

    public Optional<String> resolveDefaultModel() {
        // Precedence: ~/.andalus/config.json (authoritative) > environment
        try {
            java.nio.file.Path cfg = java.nio.file.Paths.get(System.getProperty("user.home"), ".andalus", "config.json");
            if (java.nio.file.Files.exists(cfg)) {
                String content = java.nio.file.Files.readString(cfg);
                String cfgModel = null;
                if (this.preferredProvider != null && !this.preferredProvider.isBlank()) {
                    java.util.regex.Pattern pp = java.util.regex.Pattern.compile("\\\"" + this.preferredProvider + "Model\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
                    java.util.regex.Matcher pm = pp.matcher(content);
                    if (pm.find()) {
                        cfgModel = pm.group(1).trim();
                    }
                }
                
                // Only fall back to the global defaultModel if no provider is preferred OR the preferred provider is 'aqli'
                if (cfgModel == null && (this.preferredProvider == null || this.preferredProvider.isBlank() || this.preferredProvider.equals("aqli"))) {
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\\"(?:model|defaultModel|default_model)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
                    java.util.regex.Matcher m = p.matcher(content);
                    if (m.find()) {
                        cfgModel = m.group(1).trim();
                    }
                }
                
                if (cfgModel != null) {
                    // Try to resolve to a local aqli model id by scanning 'aqli list' output.
                    try {
                        List<?> models = tech.kayys.andalus.client.AndalusAqliFacade.listModels();
                        String loweredCfg = cfgModel.toLowerCase();
                        boolean hasSlash = loweredCfg.contains("/");
                        String owner = null, name = null;
                        if (hasSlash) {
                            String[] parts = loweredCfg.split("/", 2);
                            owner = parts[0];
                            name = parts[1];
                        }

                        for (Object o : models) {
                            if (o == null) continue;
                            
                            // If it's a ModelInfo object, try to extract the modelId
                            String modelIdStr = null;
                            try {
                                java.lang.reflect.Method getModelId = o.getClass().getMethod("getModelId");
                                Object id = getModelId.invoke(o);
                                if (id != null) modelIdStr = id.toString();
                            } catch (Exception e) {
                                // not a ModelInfo object, fall back to toString
                            }
                            if (modelIdStr == null) {
                                modelIdStr = o.toString();
                            }
                            
                            String line = modelIdStr;
                            // strip ANSI color sequences
                            String plain = line.replaceAll("\u001B\\[[;\\d]*m", "");
                            String low = plain.toLowerCase();

                            // direct substring or owner/name/tokenized matching (mirrors AndalusAqliFacade.modelExists)
                            boolean matched = false;
                            if (low.contains(loweredCfg)) matched = true;
                            if (!matched && hasSlash) {
                                if (owner != null && name != null && low.contains(owner) && low.contains(name)) matched = true;
                                if (!matched && name != null && low.contains(name)) matched = true;
                            }
                            if (!matched) {
                                // token columns (ID OWNER NAME ...)
                                String[] toksAll = low.trim().split("\\s+");
                                if (toksAll.length >= 3) {
                                    String nameToken = toksAll[2];
                                    String lastSegment = hasSlash ? name : loweredCfg;
                                    if (nameToken.equalsIgnoreCase(lastSegment)) matched = true;
                                }
                            }

                            if (!matched) continue;

                            // extract leading ID token
                            java.util.regex.Matcher idm = java.util.regex.Pattern.compile("^\\s*([0-9a-fA-F]{4,})").matcher(plain);
                            if (idm.find()) {
                                return Optional.of(idm.group(1));
                            }
                            // fallback: first token from plain text
                            String[] toks = plain.trim().split("\\s+", 2);
                            if (toks.length > 0) return Optional.of(toks[0]);
                            return Optional.of(cfgModel);
                        }
                    } catch (Throwable ignore) {
                        // ignore failure, fallback to configured model
                    }
                    return Optional.of(cfgModel);
                }
            }
        } catch (Exception ignored) {
        }
        String env = System.getenv("ANDALUS_MODEL");
        if (env != null && !env.isBlank()) return Optional.of(env.trim());
        return Optional.empty();
    }

    /**
     * Returns a stable identifier for a model row: prefers modelId (the actual
     * filename / provider key) over shortId (the hex hash).
     */
    public record ModelRow(String shortId, String name, String format, String sizeStr) {
        /** The value to pass to agent.setModelId() – same as shortId field here which stores modelId. */
        public String modelId() { return shortId; }
    }

    public record ProviderRow(String id, String name, String version, String status, String defaultModel) {}

    public List<ModelRow> listModelsStructured() {
        try {
            List<?> raw = AndalusAqliFacade.listModels();
            List<ModelRow> result = new java.util.ArrayList<>();
            for (Object o : raw) {
                if (o == null) continue;

                // Attempt to extract fields via reflection (handles ModelInfo SDK objects)
                String modelId = null;
                String shortId = null;
                String name = null;
                String format = null;
                String sizeStr = null;

                try {
                    try { modelId = invokeStrMethod(o, "getModelId"); } catch (Exception ignored) {}
                    try { shortId = invokeStrMethod(o, "getShortId"); } catch (Exception ignored) {}
                    try { name    = invokeStrMethod(o, "getName"); }    catch (Exception ignored) {}
                    try { format  = invokeStrMethod(o, "getFormat"); }  catch (Exception ignored) {}
                    try { sizeStr = invokeStrMethod(o, "getSizeFormatted"); } catch (Exception ignored) {}
                } catch (Exception ignored) {}

                if (modelId != null && !modelId.isBlank()) {
                    String displayId = (shortId != null && !shortId.isBlank()) ? shortId : modelId.substring(0, Math.min(8, modelId.length()));
                    String displayName = (name != null && !name.isBlank()) ? name : modelId;
                    result.add(new ModelRow(modelId, displayName, format != null ? format : "", sizeStr != null ? sizeStr : ""));
                    continue;
                }

                // Fallback: parse the raw string line from `aqli list` shell output.
                // Expected format: ID  GROUP  NAME  ARCH  FORMAT  SIZE_NUM  SIZE_UNIT  MODIFIED
                // e.g.: "1bd979 Qwen  Qwen3.6-35B-A3B  qwen  safeten...  67 GB  2026-07-01"
                String plain = o.toString().replaceAll("\u001B\\[[;\\d]*m", "").trim();
                if (plain.isEmpty()) continue;
                // Skip header and separator lines
                if (plain.startsWith("ID") || plain.startsWith("─") || plain.startsWith("-") || plain.startsWith("=")) continue;
                // Also skip the summary line like "16 model(s) found"
                if (plain.matches("\\d+ model.*")) continue;

                String[] toks = plain.split("\\s+");
                if (toks.length < 2) continue;

                // toks[0]=ID, toks[1]=GROUP, toks[2]=NAME, toks[3]=ARCH,
                // toks[4]=FORMAT, toks[5]=SIZE_NUM, toks[6]=SIZE_UNIT, toks[7]=MODIFIED
                String id    = toks[0];
                String tName = toks.length > 2 ? toks[2] : (toks.length > 1 ? toks[1] : id);
                String tGroup = toks.length > 1 ? toks[1] : "";
                String tFormat = toks.length > 4 ? toks[4] : "";
                String tSize = "";
                if (toks.length > 6) {
                    // SIZE_NUM at index 5, SIZE_UNIT at index 6
                    tSize = toks[5] + " " + toks[6];
                } else if (toks.length > 5) {
                    tSize = toks[5];
                }

                // Build a human-readable display name: GROUP/NAME if group available
                String displayName = (!tGroup.isEmpty() && !tName.isEmpty()) ? tGroup + "/" + tName : tName;
                result.add(new ModelRow(id, displayName, tFormat, tSize));
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String invokeStrMethod(Object obj, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                java.lang.reflect.Method m = obj.getClass().getMethod(methodName);
                Object res = m.invoke(obj);
                if (res != null) return res.toString();
            } catch (Exception ignored) {}
        }
        return null;
    }


    public List<String> listModelsStrings() {
        try {
            List<?> raw = AndalusAqliFacade.listModels();
            return raw.stream().map(Object::toString).collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<ProviderRow> listAvailableProviders() {
        try {
            List<?> raw = AndalusAqliFacade.listProviders();
            List<ProviderRow> result = new java.util.ArrayList<>();
            for (Object o : raw) {
                if (o == null) continue;
                String id = null, name = null, version = null, status = null;
                try {
                    id = invokeStrMethod(o, "getId", "id");
                    name = invokeStrMethod(o, "getName", "name");
                    version = invokeStrMethod(o, "getVersion", "version");
                    Object health = null;
                    try { health = o.getClass().getMethod("getHealth").invoke(o); } catch (Exception e) {}
                    if (health == null) {
                        try { health = o.getClass().getMethod("healthStatus").invoke(o); } catch (Exception e) {}
                    }
                    if (health != null) {
                        status = invokeStrMethod(health, "status", "name", "toString");
                    }
                } catch (Exception ignored) {}
                
                if (id != null) {
                    String defaultModel = invokeStrMethod(o, "getDefaultModel", "defaultModel");
                    if (defaultModel == null) {
                        try {
                            Object meta = o.getClass().getMethod("metadata").invoke(o);
                            java.nio.file.Files.writeString(java.nio.file.Paths.get("/tmp/meta_debug.txt"), "meta class: " + (meta == null ? "null" : meta.getClass().getName()) + "\nmeta value: " + meta + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                            if (meta instanceof java.util.Map) {
                                Object dm = ((java.util.Map<?, ?>) meta).get("defaultModel");
                                if (dm != null) defaultModel = dm.toString();
                            } else if (meta != null) {
                                defaultModel = invokeStrMethod(meta, "defaultModel", "getDefaultModel");
                            }
                        } catch (Exception e) {
                            try { java.nio.file.Files.writeString(java.nio.file.Paths.get("/tmp/meta_debug.txt"), "error: " + e + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); } catch(Exception ignore) {}
                        }
                    }
                    result.add(new ProviderRow(id, name != null ? name : id, version != null ? version : "", status != null ? status : "UNKNOWN", defaultModel != null ? defaultModel : ""));
                } else {
                    // string fallback
                    result.add(new ProviderRow(o.toString(), o.toString(), "", "", ""));
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    public void setPreferredProvider(String providerId) {
        this.preferredProvider = providerId;
    }

    /**
     * Read provider configured in ~/.andalus/config.json if present.
     */
    public Optional<String> readConfigProvider() {
        try {
            java.nio.file.Path cfg = java.nio.file.Paths.get(System.getProperty("user.home"), ".andalus", "config.json");
            if (java.nio.file.Files.exists(cfg)) {
                String content = java.nio.file.Files.readString(cfg);
                java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\\"provider\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
                java.util.regex.Matcher m = p.matcher(content);
                if (m.find()) {
                    return Optional.of(m.group(1).trim());
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    public void writeConfigProvider(String providerId) {
        try {
            java.nio.file.Path cfg = java.nio.file.Paths.get(System.getProperty("user.home"), ".andalus", "config.json");
            if (java.nio.file.Files.exists(cfg)) {
                String content = java.nio.file.Files.readString(cfg);
                if (content.contains("\"provider\"")) {
                    content = content.replaceAll("\\\"provider\\\"\\s*:\\s*\\\"[^\\\"]+\\\"", "\"provider\": \"" + providerId + "\"");
                } else {
                    content = content.replaceFirst("\\}", ",\n  \"provider\": \"" + providerId + "\"\n}");
                }
                java.nio.file.Files.writeString(cfg, content);
            } else {
                java.nio.file.Files.createDirectories(cfg.getParent());
                java.nio.file.Files.writeString(cfg, "{\n  \"provider\": \"" + providerId + "\"\n}");
            }
        } catch (Exception ignored) {
        }
    }

    public Object getSystemInfo() {
        // Provide a minimal system info map
        return java.util.Map.of(
                "os", System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")",
                "java", System.getProperty("java.version"),
                "maxMemory", Runtime.getRuntime().maxMemory(),
                "totalMemory", Runtime.getRuntime().totalMemory()
        );
    }

    /**
     * Ensure model is available locally. Returns 0 on success (downloaded or already present), non-zero otherwise.
     */
    public int ensureModelAvailable(PrintStream out, String modelSpec) {
        return AndalusAqliFacade.pullModel(out, modelSpec);
    }

    public void close() {
        // no-op for adapter
    }
}
