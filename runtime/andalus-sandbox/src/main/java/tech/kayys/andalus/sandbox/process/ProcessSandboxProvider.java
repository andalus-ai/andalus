package tech.kayys.andalus.sandbox.process;

import tech.kayys.andalus.extension.Version;
import tech.kayys.andalus.spi.sandbox.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ProcessSandboxProvider implements SandboxProvider {

    public static final String ID = "andalus.sandbox.process";
    private static final Pattern SAFE_SANDBOX_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Path baseDirectory;

    public ProcessSandboxProvider(Path baseDirectory) {
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory")
                .toAbsolutePath().normalize();
    }

    @Override
    public String getProviderId() {
        return ID;
    }

    @Override
    public SandboxProviderDescriptor descriptor() {
        return new SandboxProviderDescriptor(
                ID,
                "Process Sandbox",
                "OS process based execution isolation",
                Version.parse("1.0.0"),
                Set.of(SandboxType.PROCESS, SandboxType.NONE),
                Set.of(IsolationFeature.PROCESS_ISOLATION, IsolationFeature.TEMPORARY_WORKSPACE),
                Set.of(),
                Set.of(ResourceLimitFeature.EXECUTION_TIMEOUT, ResourceLimitFeature.OUTPUT_LIMIT),
                Map.of()
        );
    }

    @Override
    public Sandbox create(SandboxRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        if (request.preferredType() != SandboxType.PROCESS
                && request.preferredType() != SandboxType.NONE) {
            throw new IllegalArgumentException(
                    "ProcessSandboxProvider cannot create " + request.preferredType());
        }

        Files.createDirectories(baseDirectory);
        Path trustedBase = baseDirectory.toRealPath();

        String sandboxId = request.sandboxId() != null
                ? request.sandboxId()
                : UUID.randomUUID().toString();
        if (!SAFE_SANDBOX_ID.matcher(sandboxId).matches()) {
            throw new IllegalArgumentException("Invalid sandbox id");
        }

        Path workspace = trustedBase.resolve(sandboxId).normalize();
        if (!workspace.startsWith(trustedBase) || workspace.equals(trustedBase)) {
            throw new SecurityException("Sandbox workspace escapes its base directory");
        }

        Files.createDirectory(workspace);
        Path realWorkspace = workspace.toRealPath();
        if (!realWorkspace.startsWith(trustedBase) || !realWorkspace.equals(workspace)) {
            throw new SecurityException("Sandbox workspace must be a real directory beneath its base");
        }

        Path input = Files.createDirectories(realWorkspace.resolve("input"));
        Path output = Files.createDirectories(realWorkspace.resolve("output"));

        return new DefaultProcessSandbox(sandboxId, request, realWorkspace, input, output);
    }
}