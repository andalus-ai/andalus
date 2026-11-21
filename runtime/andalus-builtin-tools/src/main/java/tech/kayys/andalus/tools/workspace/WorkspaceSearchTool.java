package tech.kayys.andalus.tools.workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.andalus.tool.ToolContext;
import tech.kayys.andalus.tool.ToolInvocation;
import tech.kayys.andalus.tool.ToolResult;

@ApplicationScoped
public class WorkspaceSearchTool extends WorkspaceToolSupport {
    private static final int MAX_FILES = 5_000;
    private static final int MAX_MATCHES = 200;

    public WorkspaceSearchTool() {
        super("workspace.search", "Search for a literal text fragment in files inside the authorized workspace.",
                Map.of("type", "object",
                        "properties", Map.of("query", Map.of("type", "string"),
                                "glob", Map.of("type", "string")),
                        "required", List.of("query"),
                        "additionalProperties", false));
    }

    @Override
    public CompletableFuture<ToolResult> execute(ToolInvocation invocation, ToolContext context) {
        try {
            String query = WorkspaceListTool.stringArg(invocation, "query", "");
            if (query.isBlank()) return CompletableFuture.completedFuture(
                    failure(invocation, "A non-empty query is required.", descriptor().name()));
            var root = workspaceRoot(context);
            String glob = WorkspaceListTool.stringArg(invocation, "glob", "**/*");
            var matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            List<Map<String, Object>> matches = new ArrayList<>();
            int scanned = 0;
            try (var paths = Files.walk(root, 12)) {
                var iterator = paths.iterator();
                while (iterator.hasNext() && scanned < MAX_FILES && matches.size() < MAX_MATCHES) {
                    var file = iterator.next();
                    if (!Files.isRegularFile(file)) continue;
                    scanned++;
                    var real = file.toRealPath();
                    if (!real.startsWith(root) || !matcher.matches(root.relativize(file))) continue;
                    if (Files.size(real) > MAX_FILE_BYTES) continue;
                    List<String> lines = Files.readAllLines(real, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size() && matches.size() < MAX_MATCHES; i++) {
                        if (lines.get(i).contains(query)) {
                            matches.add(Map.of("path", root.relativize(real).toString(),
                                    "line", i + 1, "text", lines.get(i)));
                        }
                    }
                }
            }
            return CompletableFuture.completedFuture(ToolResult.success(
                    invocation.invocationIdentifier(),
                    Map.of("matches", matches, "truncated", matches.size() >= MAX_MATCHES || scanned >= MAX_FILES),
                    java.time.Duration.ZERO, descriptor().name()));
        } catch (SecurityException e) {
            return CompletableFuture.completedFuture(denied(invocation, e.getMessage()));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(failure(invocation, "Unable to search workspace: " + e.getMessage(), descriptor().name()));
        }
    }
}
