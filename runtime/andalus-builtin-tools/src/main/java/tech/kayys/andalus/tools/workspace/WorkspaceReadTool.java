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
            var file = resolveExisting(context, requested);
            if (!Files.isRegularFile(file)) return CompletableFuture.completedFuture(
                    failure(invocation, "Requested workspace path is not a regular file.", descriptor().name()));
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) return CompletableFuture.completedFuture(
                    failure(invocation, "File exceeds the 1 MiB read limit.", descriptor().name()));
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return CompletableFuture.completedFuture(ToolResult.success(
                    invocation.invocationIdentifier(), Map.of("path", file.toString(), "content", content),
                    java.time.Duration.ZERO, descriptor().name()));
        } catch (SecurityException e) {
            return CompletableFuture.completedFuture(denied(invocation, e.getMessage()));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(failure(invocation, "Unable to read workspace file: " + e.getMessage(), descriptor().name()));
        }
    }
}
