package tech.kayys.andalus.tui.config;
import tech.kayys.andalus.tools.spi.Tool;
import tech.kayys.andalus.json.Json;
import tech.kayys.andalus.json.JsonValue;
import tech.kayys.andalus.json.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Application configuration: which AI providers are available, the
 * active profile (provider + model + system prompt + UI mode), and
 * agent-mode settings (tool permissions). Loaded from
 * ~/.andalus/config.json, with environment variables as a
 * fallback/override for API keys so secrets don't have to live on disk.
 */
public final class Config {

    public String globalProvider = "";
    public String runner = "";
    public String defaultModel = "";

    public enum UiMode { REPL, PANEL }
    public enum AgentMode { CHAT, AGENT }

    public List<ProviderConfig> providers = new ArrayList<>();
    public String activeProfile = "default";
    public List<Profile> profiles = new ArrayList<>();

    public static final class ProviderConfig {
        public String name;          // e.g. "anthropic", "openai", "ollama"
        public String type;          // which Provider implementation to use
        public String baseUrl;
        public String apiKeyEnv;     // name of env var holding the key
        public String apiKey;        // literal key (discouraged, but supported)
    }

    public static final class Profile {
        public String name;
        public String provider;
        public String model;
        public String systemPrompt = "";
        public UiMode uiMode = UiMode.REPL;
        public AgentMode agentMode = AgentMode.CHAT;
        public double temperature = 1.0;
        public int maxTokens = 4096;
        public boolean autoApproveTools = false; // if true, skip permission prompts
        public List<String> allowedTools = new ArrayList<>(); // empty = all tools allowed
    }

    public static Path defaultConfigDir() {
        String home = System.getProperty("user.home");
        return Paths.get(home, ".andalus");
    }

    public static Path defaultConfigPath() {
        return defaultConfigDir().resolve("config.json");
    }

    /** Loads config from disk, or returns a sensible built-in default if none exists. */
    public static Config load(Path path) {
        if (Files.exists(path)) {
            try {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                return fromJson(Json.parse(text));
            } catch (Exception e) {
                System.err.println("Warning: failed to read config at " + path + ": " + e.getMessage());
            }
        }
        return defaultConfig();
    }

    public void save(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, Json.writePretty(toJson()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Warning: failed to save config: " + e.getMessage());
        }
    }

    public Profile activeProfile() {
        for (Profile p : profiles) {
            if (p.name.equals(activeProfile)) return p;
        }
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    public ProviderConfig provider(String name) {
        for (ProviderConfig p : providers) {
            if (p.name.equals(name)) return p;
        }
        return null;
    }

    /** Resolves the API key for a provider: literal config value first, then env var. */
    public static String resolveApiKey(ProviderConfig pc) {
        if (pc.apiKey != null && !pc.apiKey.isBlank()) return pc.apiKey;
        if (pc.apiKeyEnv != null) {
            String v = System.getenv(pc.apiKeyEnv);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    public static Config defaultConfig() {
        Config c = new Config();

        ProviderConfig aqli = new ProviderConfig();
        aqli.name = "aqli";
        aqli.type = "engine";
        aqli.baseUrl = "http://localhost:8080/api/v1/coder/run";
        aqli.apiKeyEnv = null;
        c.providers.add(aqli);

        ProviderConfig anthropic = new ProviderConfig();
        anthropic.name = "anthropic";
        anthropic.type = "anthropic";
        anthropic.baseUrl = "https://api.anthropic.com";
        anthropic.apiKeyEnv = "ANTHROPIC_API_KEY";
        c.providers.add(anthropic);

        ProviderConfig openai = new ProviderConfig();
        openai.name = "openai";
        openai.type = "openai";
        openai.baseUrl = "https://api.openai.com";
        openai.apiKeyEnv = "OPENAI_API_KEY";
        c.providers.add(openai);

        ProviderConfig ollama = new ProviderConfig();
        ollama.name = "ollama";
        ollama.type = "openai"; // ollama exposes an OpenAI-compatible endpoint
        ollama.baseUrl = "http://localhost:11434";
        ollama.apiKeyEnv = null;
        c.providers.add(ollama);

        ProviderConfig demo = new ProviderConfig();
        demo.name = "demo";
        demo.type = "demo";
        demo.baseUrl = "";
        c.providers.add(demo);

        Profile def = new Profile();
        def.name = "default";
        def.provider = "aqli";
        def.model = null; // No default model, requires user to select or configure one
        def.systemPrompt = "You are a helpful coding assistant running in a terminal UI.";
        def.uiMode = UiMode.REPL;
        def.agentMode = AgentMode.AGENT;
        c.profiles.add(def);
        c.activeProfile = "default";

        return c;
    }

    // ---------- JSON (de)serialization ----------

    public JsonValue toJson() {
        JsonValue root = JsonValue.object();
        
        // Preserve CLI fields
        if (globalProvider != null && !globalProvider.isEmpty()) root.put("provider", globalProvider);
        if (runner != null && !runner.isEmpty()) root.put("runner", runner);
        if (defaultModel != null && !defaultModel.isEmpty()) root.put("defaultModel", defaultModel);
        
        JsonValue provArr = JsonValue.array();
        for (ProviderConfig pc : providers) {
            JsonValue p = JsonValue.object();
            p.put("name", pc.name);
            p.put("type", pc.type);
            p.put("baseUrl", pc.baseUrl);
            if (pc.apiKeyEnv != null) p.put("apiKeyEnv", pc.apiKeyEnv);
            if (pc.apiKey != null) p.put("apiKey", pc.apiKey);
            provArr.add(p);
        }
        root.put("providers", provArr);
        root.put("activeProfile", activeProfile);

        JsonValue profArr = JsonValue.array();
        for (Profile pr : profiles) {
            JsonValue p = JsonValue.object();
            p.put("name", pr.name);
            p.put("provider", pr.provider);
            p.put("model", pr.model);
            p.put("systemPrompt", pr.systemPrompt);
            p.put("uiMode", pr.uiMode.name());
            p.put("agentMode", pr.agentMode.name());
            p.put("temperature", pr.temperature);
            p.put("maxTokens", pr.maxTokens);
            p.put("autoApproveTools", pr.autoApproveTools);
            JsonValue tools = JsonValue.array();
            for (String t : pr.allowedTools) tools.add(JsonValue.of(t));
            p.put("allowedTools", tools);
            profArr.add(p);
        }
        root.put("profiles", profArr);
        return root;
    }

    public static Config fromJson(JsonValue root) {
        Config c = new Config();
        
        // Parse CLI fields
        if (root.has("provider")) c.globalProvider = root.get("provider").asString("");
        if (root.has("runner")) c.runner = root.get("runner").asString("");
        if (root.has("defaultModel")) c.defaultModel = root.get("defaultModel").asString("");
        
        if (root.has("providers")) {
            for (JsonValue p : root.get("providers").asArray()) {
                ProviderConfig pc = new ProviderConfig();
                pc.name = p.get("name").asString();
                pc.type = p.get("type").asString();
                pc.baseUrl = p.get("baseUrl").asString();
                pc.apiKeyEnv = p.has("apiKeyEnv") ? p.get("apiKeyEnv").asString() : null;
                pc.apiKey = p.has("apiKey") ? p.get("apiKey").asString() : null;
                c.providers.add(pc);
            }
        }
        
        c.activeProfile = root.has("activeProfile") ? root.get("activeProfile").asString("default") : "default";
        
        if (root.has("profiles")) {
            for (JsonValue p : root.get("profiles").asArray()) {
                Profile pr = new Profile();
                pr.name = p.get("name").asString();
                pr.provider = p.get("provider").asString();
                pr.model = p.get("model").asString();
                pr.systemPrompt = p.get("systemPrompt").asString("");
                pr.uiMode = UiMode.valueOf(p.has("uiMode") ? p.get("uiMode").asString("REPL") : "REPL");
                pr.agentMode = AgentMode.valueOf(p.has("agentMode") ? p.get("agentMode").asString("CHAT") : "CHAT");
                pr.temperature = p.has("temperature") ? p.get("temperature").asDouble() : 1.0;
                pr.maxTokens = p.has("maxTokens") ? p.get("maxTokens").asInt() : 4096;
                pr.autoApproveTools = p.has("autoApproveTools") ? p.get("autoApproveTools").asBoolean(false) : false;
                if (p.has("allowedTools")) {
                    for (JsonValue t : p.get("allowedTools").asArray()) pr.allowedTools.add(t.asString());
                }
                c.profiles.add(pr);
            }
        }
        
        if (c.profiles.isEmpty()) {
            Config def = defaultConfig();
            c.profiles = def.profiles;
            c.providers = def.providers;
            c.activeProfile = def.activeProfile;
        }
        return c;
    }
}
