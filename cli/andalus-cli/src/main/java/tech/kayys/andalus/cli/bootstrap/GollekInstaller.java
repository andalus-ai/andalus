package tech.kayys.andalus.cli.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

public class AqliInstaller {

    public static boolean install(AqliConfig config) {
        Path installDir = Paths.get(config.getInstallDir());
        Path binaryPath = installDir.resolve("bin").resolve("aqli");
        
        if (Files.exists(binaryPath)) {
            return true;
        }
        
        System.out.println("[Andalus] Aqli binary not found. Initiating download and install...");
        
        try {
            Files.createDirectories(installDir);
            
            String scriptSource = config.getDownloadScript();
            
            if (scriptSource != null && (scriptSource.startsWith("http://") || scriptSource.startsWith("https://"))) {
                System.out.println("[Andalus] Fetching and running install script from " + scriptSource + " ...");
                // Use curl to pipe the script to bash
                ProcessBuilder pb = new ProcessBuilder("bash", "-c", "curl -sL " + scriptSource + " | bash -s -- --target " + installDir.toString());
                pb.inheritIO();
                Process process = pb.start();
                
                boolean finished = process.waitFor(5, TimeUnit.MINUTES);
                if (finished && process.exitValue() == 0) {
                    System.out.println("[Andalus] Aqli installed successfully via remote script.");
                    return true;
                } else {
                    System.err.println("[Andalus] Failed to install Aqli via remote script. Falling back to direct binary download.");
                }
            }
            
            // Direct download fallback
            System.out.println("[Andalus] Attempting direct binary download from " + config.getDownloadUrl() + "...");
            Files.createDirectories(installDir.resolve("bin"));
            
            ProcessBuilder pb = new ProcessBuilder("curl", "-sL", "-o", binaryPath.toString(), config.getDownloadUrl());
            pb.inheritIO();
            Process process = pb.start();
            if (process.waitFor() == 0) {
                binaryPath.toFile().setExecutable(true);
                System.out.println("[Andalus] Aqli downloaded directly.");
                return true;
            } else {
                System.err.println("[Andalus] Direct download failed.");
                return false;
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[Andalus] Error during Aqli installation: " + e.getMessage());
            return false;
        }
    }
}
