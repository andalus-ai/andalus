package tech.kayys.andalus.tools.workspace;

import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.andalus.tool.ToolContext;
import tech.kayys.andalus.tool.ToolInvocation;
import tech.kayys.andalus.tool.ToolResult;

@ApplicationScoped
public class WorkspaceListTool extends WorkspaceToolSupport {
    public WorkspaceListTool() {
        super("workspace.list", "List entries in a directory inside the authorized workspace.",
                Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")),
                        "additionalProperties", false));
    }

    @Override
    public CompletableFuture<ToolResult> execute(ToolInvocation invocation, ToolContext context) {
        try {
            var root = workspaceRoot(context);
            var directory = resolveExisting(context, stringArg(invocation, "path", "."));
            if (!Files.isDirectory(directory)) return CompletableFuture.completedFuture(
                    failure(invocation, "Requested workspace path is not a directory.", descriptor().name()));
            List<java.nio.file.Path> children = new java.util.ArrayList<>();
            try (var stream = Files.newDirectoryStream(directory)) {
                for (var child : stream) {
                    if (children.size() >= 200) break;
                    try {
                        if (child.toRealPath().startsWith(root)) children.add(child);
                    } catch (Exception ignored) {
                        // Broken links and paths outside the workspace are not exposed.
                    }
                }
            }
            children.sort(Comparator.comparing(p -> p.getFileName().toString()));
            List<Map<String, Object>> entries = children.stream()
                    .map(p -> Map.<String, Object>of(
                            "name", p.getFileName().toString(),
                            "type", Files.isDirectory(p) ? "directory" : "file"))
                    .toList();
            return CompletableFuture.completedFuture(ToolResult.success(
                    invocation.invocationIdentifier(), Map.of("path", root.relativize(directory).toString(), "entries", entries),
                    java.time.Duration.ZERO, descriptor().name()));
        } catch (SecurityException e) {
            return CompletableFuture.completedFuture(denied(invocation, e.getMessage()));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(failure(invocation, "Unable to list workspace directory: " + e.getMessage(), descriptor().name()));
        }
    }

    static String stringArg(ToolInvocation invocation, String key, String fallback) {
        Object value = invocation.arguments() == null ? null : invocation.arguments().get(key);
        return value instanceof String text ? text : fallback;
    }
}
