package tech.kayys.andalus.cli.bootstrap;

import java.nio.file.Path;
import java.nio.file.Paths;

public class AqliBootstrapService {

    public static void ensureRunning(String profile) {
        Path configYaml = Paths.get(System.getProperty("user.dir"), "config", "providers", "aqli.yaml");
        if (!configYaml.toFile().exists()) {
            configYaml = Paths.get(System.getProperty("user.dir"), "Families", "andalus", "config", "providers", "aqli.yaml");
        }
        
        AqliConfig config = AqliConfig.load(configYaml, profile);
        
        // If strategy is not grpc or embedded, we might not need to start it locally (e.g. REST remote)
        if ("embedded".equals(config.getStrategy())) {
            return; // Embedded runs in-process
        }
        
        if ("grpc".equals(config.getStrategy()) && "localhost".equals(config.getGrpcHost())) {
            
            boolean isRunning = AqliHealthProbe.isReachable(config.getGrpcHost(), config.getGrpcPort());
            if (isRunning) {
                return; // Already running
            }
            
            if (config.isAutoStart()) {
                if (config.isAutoInstall()) {
                    boolean installed = AqliInstaller.install(config);
                    if (!installed && config.isFailHard()) {
                        throw new RuntimeException("Failed to install Aqli backend. Cannot proceed.");
                    }
                }
                
                boolean started = AqliProcessManager.start(config);
                if (started) {
                    boolean healthy = AqliHealthProbe.waitForHealth(config.getGrpcHost(), config.getGrpcPort(), 30);
                    if (healthy) {
                        return; // Successfully started and healthy
                    } else {
                        System.err.println("[Andalus] Aqli backend started but did not become healthy within timeout.");
                    }
                }
                
                if (config.isFailHard()) {
                    throw new RuntimeException("Aqli backend is required but failed to start or become healthy.");
                }
            } else if (config.isFailHard()) {
                throw new RuntimeException("Aqli backend is not running at " + config.getGrpcHost() + ":" + config.getGrpcPort() + " and auto-start is disabled.");
            }
            
            // If failHard is false, we proceed (Andalus config might handle fallback)
            System.err.println("[Andalus] Warning: Aqli backend is not running. Falling back to " + config.getFallbackProvider());
            // Since this runs during CLI startup, we could inject a system property to override the preferred provider
            System.setProperty("andalus.fallback.provider", config.getFallbackProvider());
        }
    }
}
