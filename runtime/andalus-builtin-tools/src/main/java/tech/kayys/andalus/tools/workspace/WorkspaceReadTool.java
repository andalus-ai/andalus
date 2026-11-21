package tech.kayys.andalus.tools.workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.andalus.tool.ToolContext;
import tech.kayys.andalus.tool.ToolInvocation;
import tech.kayys.andalus.tool.ToolResult;

@ApplicationScoped
public class WorkspaceReadTool extends WorkspaceToolSupport {
    public WorkspaceReadTool() {
        super("workspace.read", "Read a UTF-8 text file inside the authorized workspace.",
                Map.of("type", "object",
                        "properties", Map.of("path", Map.of("type", "string")),
                        "required", java.util.List.of("path"),
                        "additionalProperties", false));
    }

    @Override
    public CompletableFuture<ToolResult> execute(ToolInvocation invocation, ToolContext context) {
        try {
            String requested = WorkspaceListTool.stringArg(invocation, "path", "");
            if (requested.isBlank()) return CompletableFuture.completedFuture(
                    failure(invocation, "A relative file path is required.", descriptor().name()));
            if (requested.length() > 4_096) return CompletableFuture.completedFuture(
                    failure(invocation, "Workspace path exceeds the 4096-character limit.", descriptor().name()));
            var root = workspaceRoot(context);
            var file = resolveExisting(context, requested);
            if (!Files.isRegularFile(file)) return CompletableFuture.completedFuture(
                    failure(invocation, "Requested workspace path is not a regular file.", descriptor().name()));
            byte[] bytes;
            try (var input = Files.newInputStream(file)) {
                bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            }
            if (bytes.length > MAX_FILE_BYTES) return CompletableFuture.completedFuture(
                    failure(invocation, "File exceeds the 1 MiB read limit.", descriptor().name()));
            String content = new String(bytes, StandardCharsets.UTF_8);
            return CompletableFuture.completedFuture(ToolResult.success(
                    invocation.invocationIdentifier(), Map.of("path", root.relativize(file).toString(), "content", content),
                    java.time.Duration.ZERO, descriptor().name()));
        } catch (SecurityException e) {
            return CompletableFuture.completedFuture(denied(invocation, e.getMessage()));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(failure(invocation, "Unable to read workspace file: " + e.getMessage(), descriptor().name()));
        }
    }
}
