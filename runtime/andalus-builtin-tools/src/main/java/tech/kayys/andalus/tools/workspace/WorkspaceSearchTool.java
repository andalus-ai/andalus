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
    private static final int MAX_VISITED_ENTRIES = 20_000;
    private static final int MAX_MATCHES = 200;
    private static final long MAX_TOTAL_SCAN_BYTES = 32L * 1_024 * 1_024;

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
            if (query.length() > 1_024) return CompletableFuture.completedFuture(
                    failure(invocation, "Search query exceeds the 1024-character limit.", descriptor().name()));
            var root = workspaceRoot(context);
            String glob = WorkspaceListTool.stringArg(invocation, "glob", "**/*");
            if (glob.length() > 512) return CompletableFuture.completedFuture(
                    failure(invocation, "Search glob exceeds the 512-character limit.", descriptor().name()));
            var matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            List<Map<String, Object>> matches = new ArrayList<>();
            int scanned = 0;
            int visited = 0;
            long totalBytes = 0;
            boolean truncated = false;
            try (var paths = Files.walk(root, 12)) {
                var iterator = paths.iterator();
                while (iterator.hasNext() && visited < MAX_VISITED_ENTRIES && scanned < MAX_FILES && matches.size() < MAX_MATCHES && totalBytes < MAX_TOTAL_SCAN_BYTES) {
                    var file = iterator.next();
                    visited++;
                    if (!Files.isRegularFile(file)) continue;
                    scanned++;
                    var real = file.toRealPath();
                    if (!real.startsWith(root) || !matcher.matches(root.relativize(file))) continue;
                    long size = Files.size(real);
                    if (size > MAX_FILE_BYTES || totalBytes + size > MAX_TOTAL_SCAN_BYTES) {
                        truncated = true;
                        continue;
                    }
                    byte[] bytes;
                    try (var input = Files.newInputStream(real)) {
                        bytes = input.readNBytes(MAX_FILE_BYTES + 1);
                    }
                    if (bytes.length > MAX_FILE_BYTES) {
                        truncated = true;
                        continue;
                    }
                    totalBytes += bytes.length;
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    String[] lines = content.split("\\R", -1);
                    for (int i = 0; i < lines.length && matches.size() < MAX_MATCHES; i++) {
                        if (lines[i].contains(query)) {
                            String snippet = lines[i].length() > 1_000 ? lines[i].substring(0, 1_000) : lines[i];
                            matches.add(Map.of("path", root.relativize(real).toString(),
                                    "line", i + 1, "text", snippet));
                        }
                    }
                }
            }
            return CompletableFuture.completedFuture(ToolResult.success(
                    invocation.invocationIdentifier(),
                    Map.of("matches", matches, "truncated", truncated || matches.size() >= MAX_MATCHES || visited >= MAX_VISITED_ENTRIES || scanned >= MAX_FILES || totalBytes >= MAX_TOTAL_SCAN_BYTES),
                    java.time.Duration.ZERO, descriptor().name()));
        } catch (SecurityException e) {
            return CompletableFuture.completedFuture(denied(invocation, e.getMessage()));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(failure(invocation, "Unable to search workspace: " + e.getMessage(), descriptor().name()));
        }
    }
}
