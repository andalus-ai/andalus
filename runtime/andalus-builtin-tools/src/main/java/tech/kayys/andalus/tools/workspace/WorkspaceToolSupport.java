package tech.kayys.andalus.tools.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import tech.kayys.andalus.extension.Metadata;
import tech.kayys.andalus.identity.ResourceId;
import tech.kayys.andalus.resource.ResourceType;
import tech.kayys.andalus.tool.Tool;
import tech.kayys.andalus.tool.ToolContext;
import tech.kayys.andalus.tool.ToolDescriptor;
import tech.kayys.andalus.tool.ToolExecutionProfile;
import tech.kayys.andalus.tool.ToolInvocation;
import tech.kayys.andalus.tool.ToolKind;
import tech.kayys.andalus.tool.ToolResult;

/**
 * Shared security boundary for read-only workspace tools.
 *
 * <p>The workspace root is an administrator/application supplied {@link Path}
 * in the tool context. String values are deliberately rejected so model input
 * cannot choose or widen the host filesystem boundary.</p>
 */
abstract class WorkspaceToolSupport implements Tool {
    static final String WORKSPACE_ROOT = "andalus.workspace.root";
    static final int MAX_FILE_BYTES = 1_048_576;

    private final ToolDescriptor descriptor;

    WorkspaceToolSupport(String name, String description, Map<String, Object> schema) {
        this.descriptor = new ToolDescriptor() {
            @Override public String name() { return name; }
            @Override public String toolId() { return name; }
            @Override public String description() { return description; }
            @Override public String version() { return "1.0.0"; }
            @Override public Map<String, Object> inputSchema() { return schema; }
            @Override public ToolExecutionProfile executionProfile() {
                return new ToolExecutionProfile(false, true, true, Duration.ZERO, ToolKind.READ);
            }
        };
    }

    @Override public final ToolDescriptor descriptor() { return descriptor; }
    @Override public ResourceId id() { return descriptor.id(); }
    @Override public ResourceType type() { return descriptor.type(); }
    @Override public Metadata metadata() { return descriptor.metadata(); }

    static Path workspaceRoot(ToolContext context) throws IOException {
        if (context == null) throw new SecurityException("Trusted workspace context is required.");
        Object configured = context.getAttribute(WORKSPACE_ROOT).orElse(null);
        if (!(configured instanceof Path path)) {
            throw new SecurityException("A server-configured workspace Path is required.");
        }
        Path root = path.toRealPath();
        if (!Files.isDirectory(root)) throw new SecurityException("Configured workspace is not a directory.");
        return root;
    }

    static Path resolveExisting(ToolContext context, String relativePath) throws IOException {
        Path root = workspaceRoot(context);
        String requested = relativePath == null || relativePath.isBlank() ? "." : relativePath;
        Path input = Path.of(requested);
        if (input.isAbsolute()) throw new SecurityException("Absolute paths are not allowed.");
        Path normalized = root.resolve(input).normalize();
        if (!normalized.startsWith(root)) throw new SecurityException("Path escapes the configured workspace.");
        Path real = normalized.toRealPath();
        if (!real.startsWith(root)) throw new SecurityException("Resolved path escapes the configured workspace.");
        return real;
    }

    static ToolResult failure(ToolInvocation invocation, String message, String provider) {
        return ToolResult.failure(invocation.invocationIdentifier(), message, Duration.ZERO, provider);
    }

    static ToolResult denied(ToolInvocation invocation, String message) {
        return ToolResult.denied(invocation.invocationIdentifier(), message);
    }
}
