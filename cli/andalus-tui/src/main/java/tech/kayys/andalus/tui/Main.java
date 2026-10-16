package tech.kayys.andalus.tui;

import tech.kayys.andalus.agent.AndalusAgent;
import tech.kayys.andalus.tui.config.Config;
import tech.kayys.andalus.provider.Provider;
import tech.kayys.andalus.tui.provider.AqliModelManager;
import tech.kayys.andalus.tui.provider.AqliProviderManager;
import tech.kayys.andalus.tui.provider.ProviderFactory;
import tech.kayys.andalus.tools.spi.Tool;
import tech.kayys.andalus.tui.ui.ModelManager;
import tech.kayys.andalus.tui.ui.PanelUi;
import tech.kayys.andalus.tui.ui.ProviderManager;
import tech.kayys.andalus.tui.ui.ReplUi;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point: loads configuration, resolves the active profile (with
 * optional CLI overrides), builds the provider/tools/agent, and runs
 * the requested UI mode -- switching between REPL and panel modes if
 * the user requests it mid-session via /panel or /repl.
 */
public final class Main {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            if (System.getenv("AGENTIC_TUI_DEBUG") != null) e.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        CliArgs cli = CliArgs.parse(args);
        if (cli.help) {
            printHelp();
            return;
        }

        Path configPath = cli.configPath != null ? Paths.get(cli.configPath) : Config.defaultConfigPath();
        Config config = Config.load(configPath);

        if (cli.profileName != null) config.activeProfile = cli.profileName;
        Config.Profile profile = config.activeProfile();
        if (profile == null) {
            System.err.println("No profile configured. Edit " + configPath + " or delete it to regenerate defaults.");
            System.exit(1);
            return;
        }

        // CLI overrides apply only to this run; they don't get persisted to disk.
        if (cli.provider != null) profile.provider = cli.provider;
        if (cli.model != null) profile.model = cli.model;
        if (cli.uiMode != null) profile.uiMode = cli.uiMode;
        if (cli.agentMode != null) profile.agentMode = cli.agentMode;

        if (!config.providers.isEmpty() && config.provider(profile.provider) == null) {
            System.err.println("Profile '" + profile.name + "' references unknown provider '" + profile.provider + "'.");
            System.err.println("Configured providers: " + providerNames(config));
            System.exit(1);
            return;
        }

        // Write the default config to disk if it doesn't exist, OR if we just 
        // initialized the default profiles because it was a raw CLI config.
        if (!java.nio.file.Files.exists(configPath) || config.profiles.size() > 0) {
            // Actually, we want to save it unconditionally so that the file gets
            // the merged schema (CLI flat keys + TUI profiles).
            config.save(configPath);
        }

        Provider provider = ProviderFactory.create(config, profile);

        List<Tool> allTools = List.of(
            new tech.kayys.andalus.tool.os.ReadFileTool(),
            new tech.kayys.andalus.tool.os.WriteFileTool(),
            new tech.kayys.andalus.tool.os.EditFileTool(),
            new tech.kayys.andalus.tool.os.ListDirTool(),
            new tech.kayys.andalus.tool.os.GrepTool(),
            new tech.kayys.andalus.tool.os.BashTool()
        );
        List<Tool> tools = new ArrayList<>();
        if (profile.agentMode == Config.AgentMode.AGENT) {
            if (profile.allowedTools == null || profile.allowedTools.isEmpty()) {
                tools.addAll(allTools);
            } else {
                for (Tool t : allTools) {
                    if (profile.allowedTools.contains(t.name())) {
                        tools.add(t);
                    }
                }
            }
        }

        AndalusAgent agent = new AndalusAgent(provider, tools, profile.systemPrompt,
                profile.temperature, profile.maxTokens, profile.autoApproveTools, java.nio.file.Path.of(System.getProperty("user.dir")));

        Config.ProviderConfig pc = config.provider(profile.provider);
        if (pc != null && needsApiKey(pc) && Config.resolveApiKey(pc) == null) {
            System.out.println("Note: no API key found for provider '" + profile.provider + "' " +
                    "(expected env var " + pc.apiKeyEnv + "). Try `--provider demo` to explore the UI without one.");
        }

        Config.UiMode mode = profile.uiMode;

        // Ensure Aqli is running if it's the selected provider
        if (profile.provider != null && profile.provider.toLowerCase().contains("aqli")) {
            tech.kayys.andalus.tui.provider.AqliManager.ensureAqliRunning();
        }

        // Build model/provider managers based on provider type.
        // Aqli and engine-backed providers expose /v1/models and /v1/providers
        // from the Aqli inference server (default port 9131).
        ModelManager modelManager = null;
        ProviderManager providerManager = null;
        Config.ProviderConfig activeProviderConfig = config.provider(profile.provider);
        if (activeProviderConfig != null) {
            String type = activeProviderConfig.type;
            if ("engine".equals(type) || "aqli".equals(type)) {
                // Aqli inference API always lives on port 9131
                String aqliBase = "http://127.0.0.1:9131";
                modelManager = new AqliModelManager(aqliBase);
                providerManager = new AqliProviderManager(aqliBase);
            } else if ("openai".equals(type) && activeProviderConfig.baseUrl != null
                    && !activeProviderConfig.baseUrl.contains("openai.com")) {
                // Ollama or other OpenAI-compatible servers (e.g. localhost:11434)
                // also expose /v1/models
                modelManager = new AqliModelManager(activeProviderConfig.baseUrl);
                providerManager = new AqliProviderManager(activeProviderConfig.baseUrl);
            }
        }

        while (true) {
            String next;
            if (mode == Config.UiMode.PANEL) {
                next = new PanelUi(config, agent).run();
            } else {
                next = new ReplUi(config, agent, modelManager, providerManager).run();
            }
            if ("panel".equals(next)) { mode = Config.UiMode.PANEL; continue; }
            if ("repl".equals(next)) { mode = Config.UiMode.REPL; continue; }
            if (next != null && next.startsWith("model:")) {
                String selectedModel = next.substring(6);
                agent.setModelId(selectedModel);
                config.activeProfile().model = selectedModel;
                try { config.save(configPath); } catch (Exception ignored) {}
                System.out.println("Switched to model: " + selectedModel);
                continue;
            }
            if (next != null && next.startsWith("provider:")) {
                String selectedProvider = next.substring(9);
                config.activeProfile().provider = selectedProvider;
                try { config.save(configPath); } catch (Exception ignored) {}
                System.out.println("Switched to provider: " + selectedProvider);
                System.out.println("Please restart the application for the provider switch to fully take effect.");
                break; // Restart needed for provider change (needs new ProviderFactory creation)
            }
            break; // quit
        }
    }

    private static boolean needsApiKey(Config.ProviderConfig pc) {
        return !"demo".equals(pc.type) && pc.apiKeyEnv != null;
    }

    private static String providerNames(Config config) {
        List<String> names = new ArrayList<>();
        for (Config.ProviderConfig pc : config.providers) names.add(pc.name);
        return String.join(", ", names);
    }

    private static void printHelp() {
        System.out.println("""
                agentic-tui -- a terminal AI coding assistant

                Usage: agentic-tui [options]

                Options:
                  --provider <name>     Override the provider (e.g. anthropic, openai, ollama, demo)
                  --model <name>        Override the model name
                  --mode <repl|panel>   UI layout (default: repl)
                  --agent <chat|agent>  chat-only or full coding-agent (with file/bash tools)
                  --profile <name>      Use a named profile from the config file
                  --config <path>       Path to config.json (default: ~/.andalus/config.json)
                  --help                Show this help

                Config file: ~/.andalus/config.json (created on first run)
                API keys: set via environment variables, e.g. ANTHROPIC_API_KEY, OPENAI_API_KEY

                Try it instantly with no API key: agentic-tui --provider demo
                """);
    }

    private static final class CliArgs {
        String provider, model, profileName, configPath;
        Config.UiMode uiMode;
        Config.AgentMode agentMode;
        boolean help;

        static CliArgs parse(String[] args) {
            CliArgs c = new CliArgs();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--provider" -> c.provider = value(args, ++i);
                    case "--model" -> c.model = value(args, ++i);
                    case "--mode" -> c.uiMode = parseUiMode(value(args, ++i));
                    case "--agent" -> c.agentMode = parseAgentMode(value(args, ++i));
                    case "--profile" -> c.profileName = value(args, ++i);
                    case "--config" -> c.configPath = value(args, ++i);
                    case "--help", "-h" -> c.help = true;
                    default -> System.err.println("Unknown argument: " + a + " (use --help for usage)");
                }
            }
            return c;
        }

        private static String value(String[] args, int i) {
            return i < args.length ? args[i] : null;
        }

        private static Config.UiMode parseUiMode(String s) {
            if (s == null) return null;
            try { return Config.UiMode.valueOf(s.toUpperCase()); }
            catch (IllegalArgumentException e) { System.err.println("Invalid --mode: " + s); return null; }
        }

        private static Config.AgentMode parseAgentMode(String s) {
            if (s == null) return null;
            try { return Config.AgentMode.valueOf(s.toUpperCase()); }
            catch (IllegalArgumentException e) { System.err.println("Invalid --agent: " + s); return null; }
        }
    }
}
