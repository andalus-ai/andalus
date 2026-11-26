package tech.kayys.andalus.execution;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Application-owned mapping from authenticated execution identity to an
 * authorized local workspace.
 *
 * <p>Implementations must enforce tenant and principal isolation. Returning
 * empty means that this execution has no filesystem workspace. The resolver
 * must not derive paths from model arguments or prompt content.</p>
 */
@FunctionalInterface
public interface WorkspaceResolver {

    Optional<Path> resolve(WorkspaceRequest request);
}
