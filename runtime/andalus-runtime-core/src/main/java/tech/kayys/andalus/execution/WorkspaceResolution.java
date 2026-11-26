package tech.kayys.andalus.execution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Validates application-provided workspace resolutions before tool exposure. */
final class WorkspaceResolution {

    private WorkspaceResolution() {
    }

    static Optional<Path> resolve(WorkspaceResolver resolver, WorkspaceRequest request) {
        if (resolver == null) return Optional.empty();
        Optional<Path> configured = resolver.resolve(request);
        if (configured == null || configured.isEmpty()) return Optional.empty();
        Path supplied = configured.get();
        if (supplied == null) return Optional.empty();

        try {
            Path canonical = supplied.toRealPath();
            if (!Files.isDirectory(canonical)) {
                throw new SecurityException("Resolved workspace is not a directory");
            }
            return Optional.of(canonical);
        } catch (IOException e) {
            throw new SecurityException("Resolved workspace cannot be canonicalized", e);
        }
    }
}
