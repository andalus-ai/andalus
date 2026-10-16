package tech.kayys.andalus.tui.provider;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.Scanner;

public class AqliManager {

    private static final int AQLI_PORT = 9131;

    private static String aqliPath = "aqli";

    public static boolean isInstalled() {
        try {
            Process process = new ProcessBuilder("which", "aqli").start();
            if (process.waitFor() == 0) {
                return true;
            }
            
            // Fallback 1: ~/.local/bin/aqli
            java.io.File localInstall = new java.io.File(System.getProperty("user.home"), ".local/bin/aqli");
            if (localInstall.exists() && localInstall.canExecute()) {
                aqliPath = localInstall.getAbsolutePath();
                return true;
            }

            // Fallback 2: ~/.aqli/bin/aqli
            java.io.File defaultInstall = new java.io.File(System.getProperty("user.home"), ".aqli/bin/aqli");
            if (defaultInstall.exists() && defaultInstall.canExecute()) {
                aqliPath = defaultInstall.getAbsolutePath();
                return true;
            }
            
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isRunning() {
        try (Socket socket = new Socket("127.0.0.1", AQLI_PORT)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void install() {
        System.out.println("Installing Aqli via script...");
        try {
            Process process = new ProcessBuilder("bash", "-c", "curl -sSL https://raw.githubusercontent.com/kayys-tech/aqli/main/scripts/install.sh | bash")
                    .inheritIO()
                    .start();
            process.waitFor();
            System.out.println("Aqli installation complete.");
        } catch (Exception e) {
            System.err.println("Failed to install Aqli: " + e.getMessage());
        }
    }

    public static void start() {
        System.out.println("Starting Aqli server (" + aqliPath + ")...");
        try {
            // Start aqli serve as a detached process
            new ProcessBuilder("bash", "-c", "nohup " + aqliPath + " serve > /dev/null 2>&1 &")
                    .start();
            
            // Wait up to 5 seconds for it to start
            for (int i = 0; i < 10; i++) {
                if (isRunning()) {
                    System.out.println("Aqli is now running!");
                    return;
                }
                Thread.sleep(500);
            }
            System.err.println("Aqli started, but port " + AQLI_PORT + " is still not responding.");
        } catch (Exception e) {
            System.err.println("Failed to start Aqli: " + e.getMessage());
        }
    }

    public static void ensureAqliRunning() {
        boolean nonInteractive = System.console() == null || 
                                 Boolean.getBoolean("andalus.cli.nonInteractive") || 
                                 System.getenv("ANDALUS_NON_INTERACTIVE") != null;

        if (nonInteractive) {
            if (!isInstalled()) {
                System.err.println("Aqli is not installed and we are in non-interactive mode. Please install it manually.");
                return;
            } else if (!isRunning()) {
                System.out.println("Non-interactive mode: auto-starting Aqli.");
                start();
            }
            return;
        }

        Scanner scanner = new Scanner(System.in);

        if (!isInstalled()) {
            System.out.print("Aqli not exist, did you want me to download? or by yourself? (auto/manual): ");
            String answer = scanner.nextLine().trim().toLowerCase();
            if ("auto".equals(answer)) {
                install();
                start();
            } else {
                System.out.println("Please install Aqli manually and start it with 'aqli serve'.");
                System.exit(1);
            }
        } else if (!isRunning()) {
            System.out.print("Aqli are not running, did you want andalus to run aqli? (Y/n): ");
            String answer = scanner.nextLine().trim().toLowerCase();
            if (answer.isEmpty() || answer.equals("y") || answer.equals("yes")) {
                start();
            }
        }
    }
}
