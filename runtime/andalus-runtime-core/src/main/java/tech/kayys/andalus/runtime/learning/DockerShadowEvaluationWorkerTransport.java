package tech.kayys.andalus.runtime.learning;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Executes one already-encoded worker frame in a digest-pinned Docker image.
 *
 * This is a transport primitive, not a complete ShadowEvaluationExecutionBoundary:
 * callers still need a reviewed DTO codec and a worker that independently verifies
 * evaluator artifacts before loading them. No host directory is mounted and no
 * application environment variables are inherited by the container.
 */
public final class DockerShadowEvaluationWorkerTransport {
    private static final Pattern IMAGE_DIGEST =
            Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._/:@-]*@sha256:[a-f0-9]{64}");

    public record Limits(long memoryMiB, double cpus, int pids, int tmpfsMiB,
                         Duration timeout, int maxStderrBytes) {
        public Limits {
            if (memoryMiB < 64 || memoryMiB > 65_536 || cpus < 0.1 || cpus > 64
                    || pids < 1 || pids > 4096 || tmpfsMiB < 1 || tmpfsMiB > 1024
                    || timeout == null || timeout.isZero() || timeout.isNegative()
                    || timeout.compareTo(Duration.ofHours(1)) > 0
                    || timeout.toNanos() < 1_000_000L
                    || maxStderrBytes < 0 || maxStderrBytes > 1_048_576) {
                throw new IllegalArgumentException("invalid shadow worker resource limits");
            }
        }

        public static Limits conservativeDefaults() {
            return new Limits(1024, 1.0, 128, 64, Duration.ofMinutes(5), 64 * 1024);
        }
    }

    private final String dockerExecutable;
    private final String workerImage;
    private final Limits limits;

    public DockerShadowEvaluationWorkerTransport(String dockerExecutable, String workerImage, Limits limits) {
        if (dockerExecutable == null || !java.nio.file.Path.of(dockerExecutable).isAbsolute()) {
            throw new IllegalArgumentException("a fixed Docker executable is required");
        }
        if (workerImage == null || !IMAGE_DIGEST.matcher(workerImage).matches()) {
            throw new IllegalArgumentException("worker image must be pinned by a sha256 digest");
        }
        this.dockerExecutable = dockerExecutable;
        this.workerImage = workerImage;
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Runs a single frame and returns the worker's stdout frame; stderr is never returned to callers. */
    public byte[] exchange(byte[] requestFrame) throws IOException, InterruptedException {
        Objects.requireNonNull(requestFrame, "requestFrame");
        if (requestFrame.length == 0
                || requestFrame.length > ShadowEvaluationWorkerProtocol.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("invalid shadow worker request frame size");
        }
        ShadowEvaluationWorkerProtocol.Frame decoded = ShadowEvaluationWorkerProtocol.decode(requestFrame);
        if (decoded.type() != ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_REQUEST) {
            throw new IllegalArgumentException("only execute request frames may be sent to the worker");
        }

        ProcessBuilder builder = new ProcessBuilder(command());
        builder.environment().clear();
        Process process = builder.start();
        long deadline = System.nanoTime() + limits.timeout().toNanos();
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        CompletableFuture<Void> writer = null;
        CompletableFuture<byte[]> stdout = null;
        CompletableFuture<byte[]> stderr = null;
        try {
            writer = CompletableFuture.runAsync(() -> {
                try (OutputStream out = process.getOutputStream()) {
                    out.write(requestFrame);
                    out.flush();
                } catch (IOException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, executor);
            stdout = CompletableFuture.supplyAsync(
                    () -> readBounded(process.getInputStream(),
                            ShadowEvaluationWorkerProtocol.MAX_FRAME_BYTES), executor);
            stderr = CompletableFuture.supplyAsync(
                    () -> readBounded(process.getErrorStream(), limits.maxStderrBytes()), executor);

            if (!process.waitFor(remainingNanos(deadline), TimeUnit.NANOSECONDS)) {
                throw timeoutFailure();
            }
            await(writer, "worker request delivery failed", deadline);
            byte[] response = await(stdout, "worker response could not be read", deadline);
            await(stderr, "worker diagnostics could not be read", deadline);
            if (process.exitValue() != 0) {
                throw new IOException("shadow worker exited with status " + process.exitValue());
            }
            ShadowEvaluationWorkerProtocol.Frame responseFrame = ShadowEvaluationWorkerProtocol.decode(response);
            if (!responseFrame.requestId().equals(decoded.requestId())) {
                throw new IOException("shadow worker response request ID mismatch");
            }
            if (responseFrame.type() != ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_RESPONSE
                    && responseFrame.type() != ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_FAILURE) {
                throw new IOException("unexpected shadow worker response type");
            }
            return response;
        } catch (RuntimeException | IOException | InterruptedException failure) {
            terminate(process);
            throw failure;
        } finally {
            if (writer != null) writer.cancel(true);
            if (stdout != null) stdout.cancel(true);
            if (stderr != null) stderr.cancel(true);
            executor.shutdownNow();
            if (process.isAlive()) terminate(process);
        }
    }

    List<String> command() {
        List<String> args = new ArrayList<>(List.of(
                dockerExecutable, "run", "--rm", "--interactive",
                "--pull=never",
                "--network=none",
                "--read-only",
                "--memory=" + limits.memoryMiB() + "m",
                "--cpus=" + limits.cpus(),
                "--pids-limit=" + limits.pids(),
                "--user=65532:65532",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--log-driver=none",
                "--tmpfs=/tmp:rw,noexec,nosuid,nodev,size=" + limits.tmpfsMiB() + "m",
                workerImage));
        return List.copyOf(args);
    }

    private static byte[] readBounded(InputStream input, int maximum) {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            boolean exceeded = false;
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (total < maximum) {
                    int retained = Math.min(count, maximum - total);
                    output.write(buffer, 0, retained);
                }
                total = Math.min(maximum + 1, total + count);
                if (total > maximum) exceeded = true;
            }
            if (exceeded) throw new IOException("worker stream exceeded configured limit");
            return output.toByteArray();
        } catch (IOException e) {
            throw new java.util.concurrent.CompletionException(e);
        }
    }

    private static long remainingNanos(long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw timeoutFailure();
        return remaining;
    }

    private static IOException timeoutFailure() {
        return new IOException("shadow worker exceeded its execution timeout");
    }

    private static <T> T await(CompletableFuture<T> future, String message, long deadline)
            throws IOException, InterruptedException {
        try {
            return future.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw timeoutFailure();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null) {
                cause = ce.getCause();
            }
            if (cause instanceof IOException io) throw io;
            throw new IOException(message, cause);
        }
    }

    private static void terminate(Process process) {
        process.descendants().forEach(handle -> {
            try { handle.destroyForcibly(); } catch (RuntimeException ignored) { }
        });
        process.destroyForcibly();
        try { process.waitFor(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
