package tech.kayys.andalus.cli.bootstrap;

import java.net.InetSocketAddress;
import java.net.Socket;

public class AqliHealthProbe {

    /**
     * Checks if the Aqli gRPC server is reachable.
     * Since we might not have the full gRPC client initialized yet, 
     * a simple socket connection check is sufficient for probing startup.
     */
    public static boolean isReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean waitForHealth(String host, int port, int timeoutSeconds) {
        long endTime = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < endTime) {
            if (isReachable(host, port)) {
                return true;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
