package tech.kayys.andalus.cli;

import tech.kayys.andalus.inference.AndalusInferenceService;
import tech.kayys.andalus.agent.AndalusAgent;
import tech.kayys.andalus.agent.AndalusAgentBuilder;
import tech.kayys.andalus.agent.AndalusSessionPersistence;
import tech.kayys.andalus.provider.ChatMessage;
import tech.kayys.andalus.provider.ContentBlock;
import tech.kayys.andalus.provider.StreamEvent;
import tech.kayys.andalus.provider.ToolSpec;
import tech.kayys.andalus.provider.AndalusProvider;
import tech.kayys.aqli.sdk.core.ChatParams;
import tech.kayys.aqli.spi.Message;
import tech.kayys.aqli.spi.tool.ToolCall;
import tech.kayys.aqli.spi.tool.ToolDefinition;
import tech.kayys.andalus.json.Json;
import tech.kayys.andalus.json.JsonValue;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Launches the Andalus agentic terminal UI backed by the Aqli/Andalus
 * inference engine instead of an external HTTP provider.
 */
final class AndalusAqliTuiApp {

    private static final String BASE_SYSTEM_PROMPT =
        """
        You are Andalus, an expert AI general-purpose agent running in the terminal.

## Identity & Persona
You are Andalus — a precise, thoughtful, and highly capable assistant.
You approach tasks methodically: read before you write, verify after you change,
and always explain your reasoning. You help users answer questions, solve problems,
and orchestrate workflows.

## Core Principles
- **Think step-by-step** before acting. Use the `think` tool to plan complex tasks.
  Break large tasks into sub-tasks; state your plan in the response before executing it.
- **Read before you write.** Always use `read_file` or `list_dir` to understand current
  state before making any edits. Never assume a file's contents.
- **Prefer targeted edits.** Use `edit_file` (old→new replacement) over `write_file`
  (full overwrite) when changing existing files. Preserve structure and style.
- **Verify your changes.** After every significant edit, run `bash` to compile, lint,
  or test. Fix errors before declaring the task done.
- **Ask, don't guess.** When requirements are ambiguous, ask one focused question
  rather than making assumptions that require re-doing the work.
- **Respect existing code.** Follow the existing code style, naming conventions, and
  project structure of each file. Preserve all comments and docstrings unless
  explicitly asked to change them.

## Tool Usage Guide
### Read-only (use freely, no permission needed):
- `read_file`     — read a single file by path
- `list_dir`      — list directory contents
- `grep`          — search for patterns inside files
- `search_files`  — find files by name pattern (e.g. '*.java')
- `glob`          — expand path globs (e.g. 'src/**/*.kt')
- `think`         — reason through a problem without side effects

### Mutating (explain before use, user approval required):
- `write_file`    — create or fully overwrite a file
- `edit_file`     — targeted old→new replacement in an existing file
- `patch`         — apply a unified diff patch to a file
- `create_dir`    — create a directory tree (mkdir -p)
- `move`          — move or rename a file / directory
- `bash`          — run shell commands (builds, tests, git, package managers)

## Working with Code
- When creating new files, always create the parent directory first with `create_dir`.
- For complex refactors spanning many files, list all affected files and your plan
  using `think`, then work through them methodically.
- Always set `working_dir` in `bash` calls to the appropriate module/project root.
- When running tests, show the test output and diagnose any failures.
- Write clear, atomic git commit messages when using git via `bash`.
- Never leave debug prints, TODO stubs, or commented-out code blocks unless asked.

## Response Format
- **Be concise but complete.** Don't pad responses or repeat yourself.
- **Show relevant file snippets**, not entire large files. Use `...` to elide unchanged
  sections when quoting code.
- **Structured multi-step tasks:** state the plan first (numbered list), then execute
  each step, then summarize what changed and suggest next steps.
- **Error handling:** if a tool fails, diagnose the root cause before retrying.
  Don't blindly retry the same operation that already failed.
- **Code blocks:** always specify the language for syntax highlighting.

## Project Context
Current working directory: {cwd}
You are operating on a real local filesystem. All file paths are local.
""".replace("{cwd}", System.getProperty("user.dir"));


    public void run() {
        System.out.println(
            "\u001B[33mNote: The legacy 'tui' command has been migrated to the new Peutui framework.\u001B[0m");
        System.out.println(
            "Please use \u001B[36mandalus agent\u001B[0m to start the interactive assistant.");
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private static AndalusProvider.StreamingDelegate createDelegate(
            AndalusInferenceService svc, String modelId) {

        return (history, systemPrompt, toolSpecs, temperature, maxTokens, onEvent) -> {
            List<Message> aqliHistory = toAqliMessages(history);
            List<ToolDefinition> tools = toToolDefinitions(toolSpecs);
            ChatParams params = ChatParams.of(temperature, maxTokens);

            CountDownLatch latch = new CountDownLatch(1);
            Map<String, StringBuilder> pendingInputs = new ConcurrentHashMap<>();

            class TokenFilter {
                StringBuilder buffer = new StringBuilder();
                boolean inThought = false;
                void process(String delta) {
                    buffer.append(delta);
                    while (buffer.length() > 0) {
                        String current = buffer.toString();
                        if (inThought) {
                            int endIdx = current.indexOf("<channel|>");
                            if (endIdx != -1) {
                                String thought = current.substring(0, endIdx);
                                if (!thought.isEmpty()) onEvent.accept(new StreamEvent.ThinkingDelta(thought));
                                onEvent.accept(new StreamEvent.ThinkingEnd());
                                buffer.delete(0, endIdx + 10);
                                inThought = false;
                            } else {
                                int safeIdx = current.lastIndexOf('<');
                                if (safeIdx != -1) {
                                    if (safeIdx > 0) {
                                        onEvent.accept(new StreamEvent.ThinkingDelta(current.substring(0, safeIdx)));
                                        buffer.delete(0, safeIdx);
                                    }
                                    break;
                                } else {
                                    onEvent.accept(new StreamEvent.ThinkingDelta(current));
                                    buffer.setLength(0);
                                }
                            }
                        } else {
                            int startIdx1 = current.indexOf("<|channel>thought");
                            int startIdx2 = current.indexOf("<thought");
                            int startIdx = startIdx1;
                            int tagLen = 17; // "<|channel>thought".length()
                            if (startIdx1 == -1 || (startIdx2 != -1 && startIdx2 < startIdx1)) {
                                startIdx = startIdx2;
                                tagLen = 8; // "<thought".length()
                            }
                            
                            if (startIdx != -1) {
                                if (startIdx > 0) onEvent.accept(new StreamEvent.TextDelta(current.substring(0, startIdx)));
                                int endOfThought = startIdx + tagLen;
                                if (current.length() > endOfThought && current.charAt(endOfThought) == '\n') endOfThought++;
                                buffer.delete(0, endOfThought);
                                inThought = true;
                            } else {
                                // Also silently swallow orphaned <channel|> that sometimes leak
                                int endTagIdx = current.indexOf("<channel|>");
                                if (endTagIdx != -1) {
                                    if (endTagIdx > 0) onEvent.accept(new StreamEvent.TextDelta(current.substring(0, endTagIdx)));
                                    buffer.delete(0, endTagIdx + 10);
                                    continue;
                                }
                                
                                int safeIdx = current.lastIndexOf('<');
                                if (safeIdx != -1) {
                                    if (safeIdx > 0) {
                                        onEvent.accept(new StreamEvent.TextDelta(current.substring(0, safeIdx)));
                                        buffer.delete(0, safeIdx);
                                    }
                                    if (buffer.length() > 20) {
                                        onEvent.accept(new StreamEvent.TextDelta(buffer.substring(0, 1)));
                                        buffer.delete(0, 1);
                                    } else {
                                        break;
                                    }
                                } else {
                                    onEvent.accept(new StreamEvent.TextDelta(current));
                                    buffer.setLength(0);
                                }
                            }
                        }
                    }
                }
                void flush() {
                    if (buffer.length() > 0) {
                        String remaining = buffer.toString().replace("<channel|>", "");
                        if (inThought) {
                            if (!remaining.isEmpty()) onEvent.accept(new StreamEvent.ThinkingDelta(remaining));
                            onEvent.accept(new StreamEvent.ThinkingEnd());
                        } else {
                            if (!remaining.isEmpty()) onEvent.accept(new StreamEvent.TextDelta(remaining));
                        }
                        buffer.setLength(0);
                        inThought = false;
                    }
                }
            }
            TokenFilter filter = new TokenFilter();

            svc.inferenceStreaming(modelId, systemPrompt, aqliHistory, tools, params)
               .subscribe().with(
                        chunk -> {
                            if (chunk.isToolCallStart()) {
                                filter.flush();
                                pendingInputs.put(chunk.toolCallId(), new StringBuilder());
                                onEvent.accept(new StreamEvent.ToolUseStart(
                                    chunk.toolCallId(), chunk.toolName()));
                            } else if (chunk.isToolCallDelta()) {
                                pendingInputs.computeIfAbsent(chunk.toolCallId(),
                                    k -> new StringBuilder()).append(chunk.toolInputDelta());
                                onEvent.accept(new StreamEvent.ToolUseInputDelta(
                                    chunk.toolCallId(), chunk.toolInputDelta()));
                            } else if (chunk.isToolCallEnd()) {
                                String jsonInput = pendingInputs
                                    .getOrDefault(chunk.toolCallId(), new StringBuilder()).toString();
                                JsonValue parsed = Json.parse(jsonInput.isBlank() ? "{}" : jsonInput);
                                onEvent.accept(new StreamEvent.ToolUseEnd(
                                    chunk.toolCallId(), parsed));
                            } else if (chunk.delta() != null && !chunk.delta().isEmpty()) {
                                filter.process(chunk.delta());
                            }
                            if (chunk.finished()) {
                                filter.flush();
                                onEvent.accept(new StreamEvent.MessageStop("end_turn"));
                                latch.countDown();
                            }
                        },
                         err -> {
                             System.err.println("[AndalusAqliTuiApp] Streaming failure: " + err.getMessage());
                             err.printStackTrace(System.err);
                             onEvent.accept(new StreamEvent.Error(err.getMessage()));
                             onEvent.accept(new StreamEvent.MessageStop("error"));
                             latch.countDown();
                         }
               );

            try {
                latch.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                onEvent.accept(new StreamEvent.Error("Interrupted"));
                onEvent.accept(new StreamEvent.MessageStop("error"));
            }
        };
    }

    private static List<Message> toAqliMessages(List<ChatMessage> history) {
        List<Message> result = new ArrayList<>();
        for (ChatMessage m : history) {
            switch (m.role) {
                case USER -> {
                    List<ContentBlock.ToolResult> toolResults = extractToolResults(m.content);
                    if (!toolResults.isEmpty()) {
                        for (ContentBlock.ToolResult tr : toolResults) {
                            result.add(Message.tool(tr.toolUseId(), tr.content()));
                        }
                    } else {
                        result.add(Message.user(m.textOnly()));
                    }
                }
                case ASSISTANT -> {
                    List<ToolCall> calls = extractToolCalls(m.content);
                    if (calls.isEmpty()) {
                        result.add(Message.assistant(m.textOnly()));
                    } else {
                        result.add(Message.assistantWithToolCalls(m.textOnly(), calls));
                    }
                }
            }
        }
        return result;
    }

    private static List<ToolCall> extractToolCalls(List<ContentBlock> blocks) {
        List<ToolCall> calls = new ArrayList<>();
        for (ContentBlock b : blocks) {
            if (b instanceof ContentBlock.ToolUse tu) {
                Map<String, Object> input = tu.input() != null ? tu.input().asStringObjectMap() : Map.of();
                calls.add(ToolCall.builder()
                    .id(tu.id())
                    .name(tu.name())
                    .arguments(input)
                    .build());
            }
        }
        return calls;
    }

    private static List<ContentBlock.ToolResult> extractToolResults(List<ContentBlock> blocks) {
        List<ContentBlock.ToolResult> results = new ArrayList<>();
        for (ContentBlock b : blocks) {
            if (b instanceof ContentBlock.ToolResult tr) {
                results.add(tr);
            }
        }
        return results;
    }

    private static List<ToolDefinition> toToolDefinitions(List<ToolSpec> specs) {
        if (specs == null) return List.of();
        List<ToolDefinition> defs = new ArrayList<>();
        for (ToolSpec s : specs) {
            defs.add(ToolDefinition.builder()
                .name(s.name())
                .description(s.description())
                .parameters(s.inputSchema())
                .build());
        }
        return defs;
    }

    private static String resolveModelId() {
        String env = System.getenv("ANDALUS_MODEL");
        return (env != null && !env.isBlank()) ? env : "default";
    }
}
