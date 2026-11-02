package tech.kayys.andalus.sandbox.process;

import tech.kayys.andalus.extension.Version;
import tech.kayys.andalus.sandbox.filesystem.DefaultSandboxFilesystem;
import tech.kayys.andalus.sandbox.runtime.AbstractSandbox;
import tech.kayys.andalus.spi.sandbox.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DefaultProcessSandbox extends AbstractSandbox implements ProcessSandbox {

    private final String sandboxId;
    private final SandboxRequest request;
    private final Path workspace;
    private final Path inputDirectory;
    private final Path outputDirectory;
    private final SandboxFilesystem filesystem;
    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Process currentProcess;

    public DefaultProcessSandbox(String sandboxId, SandboxRequest request, Path workspace,
                                 Path inputDirectory, Path outputDirectory) {
        super(createDescriptor(sandboxId),
                createSandboxContext(sandboxId, request, workspace, inputDirectory, outputDirectory));
        this.sandboxId = Objects.requireNonNull(sandboxId, "sandboxId");
        this.request = Objects.requireNonNull(request, "request");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.inputDirectory = Objects.requireNonNull(inputDirectory, "inputDirectory");
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");

        List<FilesystemRoot> roots = List.of(
                new FilesystemRoot("workspace", workspace, FilesystemAccess.READ_WRITE, false),
                new FilesystemRoot("input", inputDirectory, FilesystemAccess.READ, false),
                new FilesystemRoot("output", outputDirectory, FilesystemAccess.READ_WRITE, false));
        this.filesystem = new DefaultSandboxFilesystem(FilesystemSandboxPolicy.strict(roots));
    }

    private static SandboxDescriptor createDescriptor(String sandboxId) {
        return new SandboxDescriptor(sandboxId, "Process Sandbox", "OS process execution sandbox",
                SandboxType.PROCESS, Version.parse("1.0.0"),
                Set.of("process-isolation", "temporary-workspace"), Map.of());
    }

    private static SandboxContext createSandboxContext(String sandboxId, SandboxRequest request,
                                                         Path workspace, Path inputDirectory,
                                                         Path outputDirectory) {
        return new DefaultProcessSandboxContext(sandboxId, request, createDescriptor(sandboxId),
                workspace, inputDirectory, outputDirectory, Instant.now());
    }

    @Override
    public SandboxFilesystem filesystem() {
        return filesystem;
    }

    @Override
    protected void doStart() throws Exception {
        Files.createDirectories(workspace);
        Files.createDirectories(inputDirectory);
        Files.createDirectories(outputDirectory);
    }

    @Override
    protected void doStop() {
        Process active = currentProcess;
        if (active != null && active.isAlive()) {
            terminateProcessTree(active);
        }
    }

    @Override
    protected void doDestroy() throws Exception {
        deleteWorkspace(workspace);
        ioExecutor.close();
    }

    @Override
    public CompletableFuture<ProcessExecutionResult> execute(ProcessExecutionRequest executionRequest) {
        Objects.requireNonNull(executionRequest, "executionRequest");
        if (state() != SandboxState.RUNNING) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Sandbox is not running: " + state()));
        }
        return CompletableFuture.supplyAsync(() -> executeBlocking(executionRequest), ioExecutor);
    }

    private ProcessExecutionResult executeBlocking(ProcessExecutionRequest execReq) {
        Instant started = Instant.now();
        ProcessBuilder builder = new ProcessBuilder(execReq.command());
        builder.directory(workspace.toFile());

        Map<String, String> environment = builder.environment();
        if (!execReq.inheritEnvironment()) {
            environment.clear();
        }
        environment.putAll(execReq.environment());
        environment.put("ANDALUS_SANDBOX_ID", sandboxId);
        environment.put("ANDALUS_WORKSPACE", workspace.toString());
        environment.put("ANDALUS_INPUT_DIR", inputDirectory.toString());
        environment.put("ANDALUS_OUTPUT_DIR", outputDirectory.toString());

        long outputLimit = request.limits().hasOutputLimit()
                ? request.limits().outputBytes() : -1L;
        OutputCapture capture = new OutputCapture(outputLimit);
        AtomicBoolean outputLimitExceeded = new AtomicBoolean(false);

        try {
            Process process = builder.start();
            currentProcess = process;
            CompletableFuture<String> stdoutFuture = readAsync(
                    process.getInputStream(), capture, outputLimitExceeded, process);
            CompletableFuture<String> stderrFuture = readAsync(
                    process.getErrorStream(), capture, outputLimitExceeded, process);

            boolean completed = process.waitFor(execReq.timeout().toMillis(), TimeUnit.MILLISECONDS);
            boolean timedOut = !completed;
            boolean forciblyTerminated = false;
            if (timedOut) {
                terminateProcessTree(process);
                forciblyTerminated = process.isAlive();
            }

            int exitCode = process.isAlive() ? -1 : process.exitValue();
            String stdout = stdoutFuture.join();
            String stderr = stderrFuture.join();
            Duration duration = Duration.between(started, Instant.now());
            return new ProcessExecutionResult(exitCode, stdout, stderr, duration,
                    timedOut, forciblyTerminated, outputLimitExceeded.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Process active = currentProcess;
            if (active != null) {
                terminateProcessTree(active);
            }
            throw new CompletionException(e);
        } catch (IOException e) {
            throw new CompletionException(e);
        } finally {
            currentProcess = null;
        }
    }

    private CompletableFuture<String> readAsync(InputStream inputStream, OutputCapture capture,
                                                  AtomicBoolean outputExceeded, Process process) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream input = inputStream; ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (!capture.append(stream, buffer, read)) {
                        outputExceeded.set(true);
                        terminateProcessTree(process);
                    }
                }
                return stream.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, ioExecutor);
    }

    /**
     * A single synchronized budget is shared by stdout and stderr. Readers continue
     * draining their pipes after the budget is exhausted, but retain no more bytes.
     */
    private static final class OutputCapture {
        private final long limit;
        private long captured;

        private OutputCapture(long limit) {
            this.limit = limit;
        }

        private synchronized boolean append(ByteArrayOutputStream target, byte[] bytes, int length) {
            long remaining = limit < 0 ? length : Math.max(0, limit - captured);
            int accepted = (int) Math.min(length, remaining);
            if (accepted > 0) {
                target.write(bytes, 0, accepted);
                captured += accepted;
            }
            return accepted == length;
        }
    }

    private static void terminateProcessTree(Process root) {
        ProcessHandle handle = root.toHandle();
        handle.descendants().toList().reversed().forEach(DefaultProcessSandbox::destroyHandle);
        destroyHandle(handle);
    }

    private static void destroyHandle(ProcessHandle handle) {
        if (!handle.isAlive()) {
            return;
        }
        handle.destroy();
        try {
            if (!handle.onExit().orTimeout(2, TimeUnit.SECONDS).isDone()) {
                handle.destroyForcibly();
            }
        } catch (Exception ignored) {
            handle.destroyForcibly();
        }
    }

    private static void deleteWorkspace(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}