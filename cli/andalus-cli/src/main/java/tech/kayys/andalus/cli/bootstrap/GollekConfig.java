package tech.kayys.andalus.cli.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class AqliConfig {
    private String strategy = "grpc";
    private String grpcHost = "localhost";
    private int grpcPort = 31013;
    private boolean autoStart = true;
    private boolean autoInstall = true;
    private String installDir = System.getProperty("user.home") + "/.andalus/aqli";
    private String downloadScript = "Families/aqli/scripts/install.sh";
    private String downloadUrl = "https://github.com/bhangun/aqli/releases/latest/download/aqli";
    private String fallbackProvider = "gemini";
    private boolean failHard = true;
    private String defaultModel = "gemma-2-2b-it-Q4_K_M";

    public static AqliConfig load(Path yamlFile, String activeProfile) {
        AqliConfig config = new AqliConfig();
        if (!Files.exists(yamlFile)) {
            return config;
        }

        try {
            String content = Files.readString(yamlFile);
            Map<String, String> flatProps = parseSimpleYaml(content, activeProfile);

            config.strategy = flatProps.getOrDefault("strategy", config.strategy);
            config.grpcHost = flatProps.getOrDefault("grpc.host", config.grpcHost);
            if (flatProps.containsKey("grpc.port")) {
                config.grpcPort = Integer.parseInt(flatProps.get("grpc.port"));
            }
            config.autoStart = Boolean
                    .parseBoolean(flatProps.getOrDefault("auto.start", String.valueOf(config.autoStart)));
            config.autoInstall = Boolean
                    .parseBoolean(flatProps.getOrDefault("auto.install", String.valueOf(config.autoInstall)));

            config.installDir = flatProps.getOrDefault("install.dir", config.installDir).replace("~",
                    System.getProperty("user.home"));
            config.downloadScript = flatProps.getOrDefault("download.script", config.downloadScript);
            config.downloadUrl = flatProps.getOrDefault("download.url", config.downloadUrl);
            config.fallbackProvider = flatProps.getOrDefault("fallback.provider", config.fallbackProvider);
            config.failHard = Boolean
                    .parseBoolean(flatProps.getOrDefault("fail.hard", String.valueOf(config.failHard)));
            config.defaultModel = flatProps.getOrDefault("default.model", config.defaultModel);

        } catch (IOException e) {
            System.err.println("Failed to read Aqli config: " + e.getMessage());
        }
        return config;
    }

    private static Map<String, String> parseSimpleYaml(String yaml, String profile) {
        Map<String, String> props = new HashMap<>();
        String[] lines = yaml.split("\\r?\\n");
        boolean inProperties = false;
        boolean inProfile = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#"))
                continue;

            int indent = line.length() - line.stripLeading().length();

            if (indent == 0) {
                if (trimmed.startsWith("properties:")) {
                    inProperties = true;
                    inProfile = false;
                } else if (trimmed.startsWith("profiles:")) {
                    inProperties = false;
                    inProfile = false;
                } else {
                    inProperties = false;
                    inProfile = false;
                }
            } else if (indent == 2 && !inProperties) {
                if (trimmed.startsWith(profile + ":")) {
                    inProfile = true;
                } else {
                    inProfile = false;
                }
            } else if ((inProperties && indent == 2) || (inProfile && indent == 6)) {
                String[] parts = trimmed.split(":", 2);
                if (parts.length == 2) {
                    props.put(parts[0].trim(), parts[1].trim());
                }
            }
        }
        return props;
    }

    public String getStrategy() {
        return strategy;
    }

    public String getGrpcHost() {
        return grpcHost;
    }

    public int getGrpcPort() {
        return grpcPort;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    public boolean isAutoInstall() {
        return autoInstall;
    }

    public String getInstallDir() {
        return installDir;
    }

    public String getDownloadScript() {
        return downloadScript;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public String getFallbackProvider() {
        return fallbackProvider;
    }

    public boolean isFailHard() {
        return failHard;
    }

    public String getDefaultModel() {
        return defaultModel;
    }
}
