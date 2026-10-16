package tech.kayys.andalus.cli.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AqliProcessManager {
    
    private static Process aqliProcess;

    public static boolean start(AqliConfig config) {
        Path installDir = Paths.get(config.getInstallDir());
        Path binaryPath = installDir.resolve("bin").resolve("aqli");
        
        if (!Files.exists(binaryPath)) {
            System.err.println("[Andalus] Cannot start Aqli. Binary not found at " + binaryPath);
            return false;
        }

        try {
            System.out.println("[Andalus] Starting Aqli backend on port " + config.getGrpcPort() + "...");
            ProcessBuilder pb = new ProcessBuilder(
                binaryPath.toString(),
                "serve",
                "--grpc-port", String.valueOf(config.getGrpcPort())
            );
            
            // Redirect output to logs
            Path logDir = Paths.get(System.getProperty("user.home"), ".andalus", "logs");
            Files.createDirectories(logDir);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logDir.resolve("aqli-backend.log").toFile()));
            
            aqliProcess = pb.start();
            
            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (aqliProcess != null && aqliProcess.isAlive()) {
                    System.out.println("[Andalus] Stopping Aqli backend...");
                    aqliProcess.destroy();
                }
            }));
            
            return true;
        } catch (IOException e) {
            System.err.println("[Andalus] Failed to start Aqli process: " + e.getMessage());
            return false;
        }
    }
    
    public static void stop() {
        if (aqliProcess != null && aqliProcess.isAlive()) {
            aqliProcess.destroy();
        }
    }
}
