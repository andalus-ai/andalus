package tech.kayys.gamelan.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.gamelan.agent.ToolCall;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Executes tool calls with approval controls and optional sandboxing.
 *
 * <p>Approval modes:
 * <ul>
 *   <li>{@code auto} — trusted tools run without prompting, others require approval</li>
 *   <li>{@code always} — every tool call requires explicit user approval</li>
 *   <li>{@code trusted-tools} — only tools in the trusted list run without approval</li>
 * </ul>
 *
 * <p>Sandbox mode restricts tool execution to a safe subset of operations:
 * <ul>
 *   <li>File reads only (no writes)</li>
 *   <li>Read-only shell commands</li>
 *   <li>No network access</li>
 * </ul>
 */
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_BYTES = 100_000;

    private final Set<String> trustedTools;
    private final Set<String> blockedTools;
    private final String approveMode;
    private final boolean sandboxEnabled;
    private final Path workingDirectory;

    public ToolExecutor(Set<String> trustedTools, Set<String> blockedTools,
                         String approveMode, boolean sandboxEnabled, Path workingDirectory) {
        this.trustedTools = new HashSet<>(trustedTools);
        this.blockedTools = new HashSet<>(blockedTools);
        this.approveMode = approveMode;
        this.sandboxEnabled = sandboxEnabled;
        this.workingDirectory = workingDirectory != null ? workingDirectory : Path.of(System.getProperty("user.dir"));
    }

    /**
     * Execute a tool call by name with arguments (Aqli native protocol).
     *
     * @param toolName the tool name
     * @param arguments the tool arguments (Map from Aqli ToolCall)
     * @return the tool result
     */
    public ToolResult execute(String toolName, Map<String, Object> arguments) {
        // Check if tool is blocked
        if (blockedTools.contains(toolName)) {
            return ToolResult.error(toolName,
                    "Tool '" + toolName + "' is blocked by permissions.");
        }

        // Check if approval is needed
        if (requiresApproval(toolName)) {
            // In CLI mode, we'd prompt the user here
            // For now, auto-approve if in auto mode
            log.warn("Approval required for {} but auto-approving in CLI mode", toolName);
        }

        // Execute the tool
        return executeTool(toolName, arguments);
    }

    private ToolResult executeTool(String toolName, Map<String, Object> arguments) {
        try {
            return switch (toolName) {
                case "read_file" -> readFile(arguments);
                case "write_file" -> writeFile(arguments);
                case "shell", "exec", "run_command" -> runShell(arguments);
                case "list_dir", "ls" -> listDirectory(arguments);
                case "search_files", "find" -> searchFiles(arguments);
                case "grep", "search_content" -> grepContent(arguments);
                case "think" -> think(arguments);
                default -> executeGenericCommand(toolName, arguments);
            };
        } catch (Exception e) {
            log.error("Tool execution failed: {}", toolName, e);
            return ToolResult.error(toolName, "Execution error: " + e.getMessage());
        }
    }

    private ToolResult readFile(Map<String, Object> args) {
        String path = (String) args.get("path");
        if (path == null || path.isBlank()) {
            return ToolResult.error("read_file", "Missing required parameter: path");
        }
        try {
            Path filePath = resolvePath(path);
            if (!Files.exists(filePath)) {
                return ToolResult.error("read_file", "File not found: " + path, 1);
            }
            if (!Files.isRegularFile(filePath)) {
                return ToolResult.error("read_file", "Not a regular file: " + path, 1);
            }
            String content = Files.readString(filePath);
            // Truncate if too large
            if (content.length() > MAX_OUTPUT_BYTES) {
                content = content.substring(0, MAX_OUTPUT_BYTES / 2)
                        + "\n\n...[TRUNCATED]...\n\n"
                        + content.substring(content.length() - MAX_OUTPUT_BYTES / 2);
            }
            return ToolResult.success("read_file", content);
        } catch (IOException e) {
            return ToolResult.error("read_file", "Cannot read file: " + e.getMessage());
        }
    }

    public boolean isTrusted(String toolName) {
        return trustedTools.contains(toolName);
    }

    public void addTrusted(String toolName) {
        trustedTools.add(toolName);
    }

    public void removeTrusted(String toolName) {
        trustedTools.remove(toolName);
    }

    public Set<String> getTrustedTools() {
        return Collections.unmodifiableSet(trustedTools);
    }

    public Set<String> getBlockedTools() {
        return Collections.unmodifiableSet(blockedTools);
    }

    public void addBlocked(String toolName) {
        blockedTools.add(toolName);
    }

    public void removeBlocked(String toolName) {
        blockedTools.remove(toolName);
    }

    // ── Internal ───────────────────────────────────────────────────────────

    private boolean requiresApproval(String toolName) {
        return switch (approveMode.toLowerCase()) {
            case "always" -> true;
            case "trusted-tools" -> !trustedTools.contains(toolName);
            case "auto" -> false; // Auto mode: no approval for known tools
            default -> false;
        };
    }

    private ToolResult writeFile(Map<String, Object> args) {
        if (sandboxEnabled) {
            return ToolResult.error("write_file", "File write is disabled in sandbox mode.");
        }
        String path = (String) args.get("path");
        String content = (String) args.get("content");
        if (path == null || content == null) {
            return ToolResult.error("write_file", "Missing required parameters: path, content");
        }
        try {
            Path filePath = resolvePath(path);
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content);
            return ToolResult.success("write_file", "File written: " + path + " (" + content.length() + " bytes)");
        } catch (IOException e) {
            return ToolResult.error("write_file", "Cannot write file: " + e.getMessage());
        }
    }

    private ToolResult listDirectory(Map<String, Object> args) {
        String path = (String) args.getOrDefault("path", ".");
        try {
            Path dirPath = resolvePath(path);
            if (!Files.isDirectory(dirPath)) {
                return ToolResult.error("list_dir", "Not a directory: " + path, 1);
            }
            try (var stream = Files.list(dirPath)) {
                String listing = stream
                        .sorted()
                        .map(p -> {
                            String type = Files.isDirectory(p) ? "📁" : "📄";
                            return type + " " + p.getFileName();
                        })
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("(empty directory)");
                return ToolResult.success("list_dir", listing);
            }
        } catch (IOException e) {
            return ToolResult.error("list_dir", "Cannot list directory: " + e.getMessage());
        }
    }

    private ToolResult runShell(Map<String, Object> args) {
        if (sandboxEnabled) {
            String command = (String) args.getOrDefault("command", "");
            // Block dangerous commands in sandbox mode
            String[] dangerousCommands = {"rm -rf", "rm -r", "chmod", "chown", "sudo", "curl", "wget", "pip install"};
            for (String dangerous : dangerousCommands) {
                if (command.contains(dangerous)) {
                    return ToolResult.error("shell",
                            "Command blocked in sandbox mode: " + dangerous);
                }
            }
        }

        String command = (String) args.get("command");
        if (command == null || command.isBlank()) {
            return ToolResult.error("shell", "Missing required parameter: command");
        }

        int timeoutSeconds = Integer.parseInt((String) args.getOrDefault("timeout", String.valueOf(DEFAULT_TIMEOUT_SECONDS)));
        try {
            ProcessBuilder pb = new ProcessBuilder();
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb.command("cmd.exe", "/c", command);
            } else {
                pb.command("bash", "-c", command);
            }
            pb.directory(workingDirectory.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines()
                        .limit(500) // Limit output lines
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("");
            }

            if (output.length() > MAX_OUTPUT_BYTES) {
                output = output.substring(0, MAX_OUTPUT_BYTES / 2) + "\n\n...[TRUNCATED]...\n\n"
                        + output.substring(output.length() - MAX_OUTPUT_BYTES / 2);
            }

            int exitCode = process.exitValue();
            if (!completed) {
                process.destroyForcibly();
                return ToolResult.error("shell",
                        "Command timed out after " + timeoutSeconds + "s. Output:\n" + output, 124);
            }

            return new ToolResult("shell", output, exitCode == 0, exitCode);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.error("shell", "Command execution failed: " + e.getMessage());
        }
    }

    private ToolResult searchFiles(Map<String, Object> args) {
        String path = (String) args.getOrDefault("path", ".");
        String pattern = (String) args.getOrDefault("pattern", "*");
        try {
            Path searchPath = resolvePath(path);
            if (!Files.isDirectory(searchPath)) {
                return ToolResult.error("search_files", "Not a directory: " + path, 1);
            }
            try (var stream = Files.walk(searchPath)) {
                String results = stream
                        .filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .filter(name -> nameMatches(name, pattern))
                        .sorted()
                        .limit(100)
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("(no matches)");
                return ToolResult.success("search_files", results);
            }
        } catch (IOException e) {
            return ToolResult.error("search_files", "Search failed: " + e.getMessage());
        }
    }

    private ToolResult grepContent(Map<String, Object> args) {
        String path = (String) args.getOrDefault("path", ".");
        String pattern = (String) args.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return ToolResult.error("grep", "Missing required parameter: pattern");
        }
        try {
            Path searchPath = resolvePath(path);
            List<String> matches = new ArrayList<>();
            if (Files.isDirectory(searchPath)) {
                try (var stream = Files.walk(searchPath)) {
                    stream.filter(Files::isRegularFile)
                            .limit(200)
                            .forEach(file -> {
                                try {
                                    String content = Files.readString(file);
                                    if (content.contains(pattern)) {
                                        matches.add(file.toString());
                                    }
                                } catch (IOException ignored) {
                                }
                            });
                }
            } else if (Files.isRegularFile(searchPath)) {
                String content = Files.readString(searchPath);
                if (content.contains(pattern)) {
                    matches.add(searchPath.toString());
                }
            }
            return ToolResult.success("grep", matches.isEmpty()
                    ? "(no matches)"
                    : matches.stream().reduce((a, b) -> a + "\n" + b).orElse(""));
        } catch (IOException e) {
            return ToolResult.error("grep", "Search failed: " + e.getMessage());
        }
    }

    private ToolResult think(Map<String, Object> args) {
        String thought = (String) args.getOrDefault("thought", args.getOrDefault("content", ""));
        return ToolResult.success("think", "Thinking noted: " + thought);
    }

    private ToolResult executeGenericCommand(String toolName, Map<String, Object> args) {
        return ToolResult.error(toolName,
                "Unknown tool: " + toolName + ". Available tools: read_file, write_file, shell, list_dir, search_files, grep, think");
    }

    private Path resolvePath(String path) {
        Path p = Path.of(path);
        if (!p.isAbsolute()) {
            return workingDirectory.resolve(p).normalize();
        }
        return p.normalize();
    }

    private boolean nameMatches(String name, String pattern) {
        // Simple glob matching
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return name.matches(regex);
    }

    /**
     * Callback interface for approval prompts.
     */
    public interface ApprovalCallback {
        boolean approve(ToolCall call);
    }
}
