package tech.kayys.andalus.tui.ui;

import tech.kayys.andalus.agent.AndalusAgent;
import tech.kayys.andalus.agent.AndalusAgentListener;
import tech.kayys.andalus.agent.PermissionDecision;
import tech.kayys.andalus.json.Json;
import tech.kayys.andalus.json.JsonValue;
import tech.kayys.andalus.tools.spi.Tool;
import tech.kayys.andalus.tools.spi.ToolResult;
import tech.kayys.andalus.tui.config.Config;
import tech.kayys.andalus.tui.render.TextWrap;
import tech.kayys.andalus.tui.render.Theme;
import tech.kayys.andalus.tui.term.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.List;

/**
 * The primary "scrolling REPL" UI for Andalus Code, modeled after Claude Code / Copilot CLI:
 * normal terminal scrollback for the conversation, with a fixed input bar
 * pinned to the bottom of the screen via a VT100 scroll region (DECSTBM).
 *
 * Falls back to a simple line-buffered mode automatically when stdin/stdout
 * isn't an interactive TTY (piped input, CI, etc).
 */
public final class ReplUi {

    private final Config config;
    private final AndalusAgent agent;
    private final ModelManager modelManager;
    private final ProviderManager providerManager;

    private final TerminalMode termMode = new TerminalMode();
    private TermOut out;
    private KeyDecoder keys;
    private final InputBuffer input = new InputBuffer();
    private final Object termLock = new Object();

    private volatile boolean running = true;
    private volatile boolean streaming = false;
    private volatile Consumer<PermissionDecision> pendingPermissionResponder;
    private volatile String pendingToolName;
    private volatile Thread agentThread;

    private volatile int polledCols = 80, polledRows = 24;
    private int cols = 80, rows = 24;
    private int inputHeight = 2;
    private int scrollBottom = 0; // 0 = not yet initialized

    private String exitAction = null; // null = quit, or "panel" to request switching UI modes
    private boolean expectingModelSelection = false;
    
    private java.util.function.Function<String, Boolean> externalSlashHandler;

    public void setExternalSlashHandler(java.util.function.Function<String, Boolean> handler) {
        this.externalSlashHandler = handler;
    }

    public interface PickerCallback {
        List<GenericPickerWidget.PickerItem> getItems();
        void onSelect(String id);
    }
    
    public interface MultiPickerCallback {
        List<GenericPickerWidget.PickerItem> getItems();
        void onSelect(List<String> ids);
    }
    
    private final Map<String, PickerCallback> pickerCallbacks = new HashMap<>();
    private final Map<String, MultiPickerCallback> multiPickerCallbacks = new HashMap<>();
    
    public void registerPicker(String command, PickerCallback callback) {
        pickerCallbacks.put(command, callback);
    }

    public void registerMultiPicker(String command, MultiPickerCallback callback) {
        multiPickerCallbacks.put(command, callback);
    }

    // --- Autocomplete State ---
    private boolean autocompleteActive = false;
    private List<String> autocompleteOptions = new ArrayList<>();
    private int autocompleteSelectionIndex = 0;
    private static final List<String> KNOWN_COMMANDS = List.of(
        "/help", "/clear", "/panel", "/quit", "/exit", 
        "/skills", "/models", "/models pull", "/providers", "/provider", "/tools", 
        "/sessions", "/sessions resume", "/sessions fork", "/sessions delete", 
        "/projects", "/project", "/status", "/info"
    );

    public void appendBlockLines(List<String> lines) {
        appendBlock(lines);
    }

    /**
     * @param config       TUI configuration (active profile determines which model is displayed)
     * @param agent        the Andalus agent that executes turns
     * @param modelManager optional model-list provider for the /models command; may be null
     * @param providerManager optional provider-list provider for the /providers command; may be null
     */
    public ReplUi(Config config, AndalusAgent agent, ModelManager modelManager, ProviderManager providerManager) {
        this.config = config;
        this.agent = agent;
        this.modelManager = modelManager;
        this.providerManager = providerManager;
    }

    /** Runs the REPL until the user quits. Returns "panel" if they requested switching UI modes, else null. */
    public String run() throws IOException {
        PrintStream utf8Out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(utf8Out);
        out = new TermOut(new FileOutputStream(FileDescriptor.out));
        keys = new KeyDecoder(System.in);

        boolean raw = termMode.enterRaw();
        try {
            if (raw) {
                runInteractive();
            } else {
                runSimple();
            }
        } finally {
            if (raw) {
                out.resetScrollRegion();
                out.showCursor();
                out.moveTo(rows, 1);
                out.flush();
            }
            termMode.restore();
        }
        return exitAction;
    }

    // =========================================================================================
    // Simple (non-TTY) fallback: cooked line-buffered mode. Used for piped/CI/non-interactive use.
    // =========================================================================================

    private void runSimple() throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.println(bannerPlain());
        System.out.println("(No interactive terminal detected -- using line mode. Type /help for commands.)");
        System.out.println();

        String line;
        while (running && (line = br.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty()) continue;

            if (expectingModelSelection && line.matches("\\d+")) {
                line = "/models " + line;
            }
            expectingModelSelection = false;

            if (line.startsWith("/")) {
                if (!handleSlashSimple(line)) break;
                continue;
            }
            runTurnSimple(line, br);
        }
    }

    private void runTurnSimple(String text, BufferedReader br) {
        AndalusAgentListener listener = new AndalusAgentListener() {
            private boolean isThinking = false;
            
            @Override public void onThinkingDelta(String t) {
                if (!isThinking) {
                    System.out.println("\u001B[90m[Thinking...]"); // Dim gray
                    isThinking = true;
                }
                System.out.print("\u001B[90m" + t.replace("\r\n", "\n").replace("\n", "\r\n") + "\u001B[0m");
                System.out.flush();
            }

            @Override public void onThinkingEnd() {
                if (isThinking) {
                    System.out.println();
                    isThinking = false;
                }
            }

            @Override public void onTextDelta(String t) { System.out.print(t.replace("\r\n", "\n").replace("\n", "\r\n")); System.out.flush(); }

            @Override public void onToolCallStart(String id, String name) {
                System.out.println();
                System.out.println("→ " + name);
            }

            @Override public void onToolCallReady(String id, String name, java.util.Map<String, Object> in) {
                System.out.println("  " + summarizeArgs(in));
            }

            @Override public void onToolPermissionNeeded(String id, String name, java.util.Map<String, Object> in, Consumer<PermissionDecision> responder) {
                System.out.print("  Allow '" + name + "' to run? [y]es/[n]o/[a]lways: ");
                System.out.flush();
                try {
                    String resp = br.readLine();
                    responder.accept(parseDecision(resp));
                } catch (IOException e) {
                    responder.accept(PermissionDecision.DENY);
                }
            }

            @Override public void onToolResult(String id, String name, ToolResult result) {
                String out = result.output().orElse(result.error() != null ? result.error() : "");
                System.out.println((!result.success() ? "✗ " : "✓ ") + name);
                if (!out.isBlank()) {
                    String[] lines = out.split("\n", -1);
                    int max = Math.min(lines.length, 12);
                    for (int i = 0; i < max; i++) {
                        System.out.println("  " + lines[i]);
                    }
                    if (lines.length > max) {
                        System.out.println("  (" + (lines.length - max) + " more lines)");
                    }
                }
            }

            @Override public void onUsage(int in, int outTok) { /* not shown in simple mode */ }

            @Override public void onDone(String reason) { System.out.println(); System.out.println(); }

            @Override public void onError(String message) {
                System.out.println();
                System.out.println("Error: " + message);
                System.out.println();
            }
        };
        agent.send(text, listener);
    }

    private PermissionDecision parseDecision(String resp) {
        if (resp == null) return PermissionDecision.DENY;
        resp = resp.strip().toLowerCase();
        if (resp.startsWith("a")) return PermissionDecision.APPROVE_ALWAYS_THIS_TOOL;
        if (resp.startsWith("y")) return PermissionDecision.APPROVE_ONCE;
        return PermissionDecision.DENY;
    }

    /** Returns false if the command requests quitting. */
    private boolean handleSlashSimple(String cmd) {
        String[] parts = cmd.split("\\s+", 2);
        switch (parts[0]) {
            case "/quit", "/exit" -> { return false; }
            case "/help" -> { for (String l : helpLines()) System.out.println(stripAnsiForPlain(l)); }
            case "/clear" -> { agent.clearHistory(); System.out.println("History cleared."); }
            case "/panel" -> { exitAction = "panel"; return false; }
            case "/models" -> {
                if (parts.length > 1) {
                    String arg = parts[1].trim();
                    if (arg.startsWith("pull ")) {
                        String modelSpec = arg.substring(5).trim();
                        if (modelManager != null) {
                            modelManager.pullModel(System.out, modelSpec);
                        } else {
                            System.out.println("Model pulling not supported.");
                        }
                    } else {
                        String modelId = arg.startsWith("set ") ? arg.substring(4).trim() : arg;
                        if (modelId.matches("\\d+") && modelManager != null) {
                            int idx = Integer.parseInt(modelId) - 1;
                            List<ModelManager.ModelRow> models = modelManager.listModels();
                            if (idx >= 0 && idx < models.size()) {
                                modelId = models.get(idx).shortId();
                            }
                        }
                        if (agent != null) {
                            agent.setModelId(modelId);
                            if (config != null && config.activeProfile() != null) {
                                config.activeProfile().model = modelId;
                                try { config.save(Config.defaultConfigPath()); } catch (Exception ignored) {}
                            }
                            System.out.println("Model assigned: " + modelId);
                        }
                    }
                } else {
                    expectingModelSelection = true;
                    printModelsSimple();
                }
            }
            case "/tools" -> { printToolsSimple(); }
            default -> {
                if (externalSlashHandler != null) {
                    return !externalSlashHandler.apply(cmd);
                } else {
                    System.out.println("Unknown command: " + parts[0] + " (try /help)");
                }
            }
        }
        return true;
    }
    
    private void printToolsSimple() {
        if (agent == null) { System.out.println("Agent not available."); return; }
        java.util.Collection<tech.kayys.andalus.tools.spi.Tool> tools = agent.tools();
        if (tools == null || tools.isEmpty()) {
            System.out.println("No tools available.");
        } else {
            System.out.println("Available tools (" + tools.size() + "):");
            for (tech.kayys.andalus.tools.spi.Tool t : tools) {
                System.out.println("  - " + t.name() + ": " + t.description());
            }
        }
    }

    private void printModelsSimple() {
        if (modelManager == null) { System.out.println("Model list not available."); return; }
        List<ModelManager.ModelRow> models = modelManager.listModels();
        if (models.isEmpty()) {
            System.out.println("No models found. Run: /models pull <model>");
        } else {
            System.out.println("Available models:");
            int i = 1;
            for (ModelManager.ModelRow m : models) {
                System.out.println("  [" + i + "] " + m.shortId() + "  " + m.format() + "  " + m.sizeStr());
                i++;
            }
            System.out.println("\nType /models <number> to assign a model.");
        }
    }

    // =========================================================================================
    // Interactive raw-mode UI
    // =========================================================================================

    private void runInteractive() throws IOException {
        Thread poller = new Thread(this::pollSizeLoop, "size-poller");
        poller.setDaemon(true);
        poller.start();

        int[] initial = TerminalMode.size();
        polledCols = initial[0];
        polledRows = initial[1];
        cols = initial[0];
        rows = initial[1];
        inputHeight = Math.max(2, input.lineCount() + 1);
        scrollBottom = Math.max(1, rows - inputHeight);

        out.write(Ansi.CLEAR_SCREEN);
        out.write(Ansi.CURSOR_HOME);
        out.setScrollRegion(1, scrollBottom);
        appendBlock(bannerLines());
        redrawInputBox();

        while (running) {
            Key key;
            try {
                key = keys.readKey();
            } catch (IOException e) {
                break;
            }

            if (pendingPermissionResponder != null) {
                handlePermissionKey(key);
                continue;
            }

            if (streaming) {
                handleKeyWhileStreaming(key);
                continue;
            }

            handleKeyIdle(key);
        }
    }

    private void pollSizeLoop() {
        while (running) {
            int[] s = TerminalMode.size();
            polledCols = s[0];
            polledRows = s[1];
            try { Thread.sleep(400); } catch (InterruptedException e) { return; }
        }
    }

    private void handlePermissionKey(Key key) {
        PermissionDecision decision = null;
        if (key.isChar()) {
            char c = Character.toLowerCase((char) key.codePoint());
            if (c == 'y') decision = PermissionDecision.APPROVE_ONCE;
            else if (c == 'a') decision = PermissionDecision.APPROVE_ALWAYS_THIS_TOOL;
            else if (c == 'n') decision = PermissionDecision.DENY;
        } else if (key.kind() == Key.Kind.ESCAPE || key.kind() == Key.Kind.CTRL_C) {
            decision = PermissionDecision.DENY;
        }
        if (decision == null) return;

        String label = switch (decision) {
            case APPROVE_ONCE -> "approved (once)";
            case APPROVE_ALWAYS_THIS_TOOL -> "approved (always for " + pendingToolName + ")";
            case DENY -> "denied";
        };
        String color = decision == PermissionDecision.DENY ? Theme.ERROR : Theme.TOOL_OK;
        appendBlock(List.of(Ansi.fg(color) + "  " + label + Ansi.RESET));

        Consumer<PermissionDecision> responder = pendingPermissionResponder;
        pendingPermissionResponder = null;
        pendingToolName = null;
        responder.accept(decision);
    }

    private void handleKeyWhileStreaming(Key key) {
        switch (key.kind()) {
            case CTRL_C, ESCAPE -> {
                // Interrupt the running agent turn
                Thread t = agentThread;
                if (t != null) t.interrupt();
                // Show cancellation message in the scroll region
                appendBlock(List.of(
                    Ansi.fg(Theme.WARN) + "  ⊘ Interrupted" + Ansi.RESET
                ));
            }
            case ENTER, SHIFT_ENTER, ALT_ENTER, NEWLINE -> { /* ignore submission while busy */ }
            default -> input.apply(key);
        }
    }

    private void handleKeyIdle(Key key) {
        // Intercept navigation/selection if autocomplete is active
        if (autocompleteActive) {
            switch (key.kind()) {
                case ARROW_UP -> {
                    autocompleteSelectionIndex--;
                    if (autocompleteSelectionIndex < 0) autocompleteSelectionIndex = autocompleteOptions.size() - 1;
                    redrawInputBox();
                    return;
                }
                case ARROW_DOWN -> {
                    autocompleteSelectionIndex++;
                    if (autocompleteSelectionIndex >= autocompleteOptions.size()) autocompleteSelectionIndex = 0;
                    redrawInputBox();
                    return;
                }
                case TAB -> {
                    // Always expand into buffer for further editing
                    if (autocompleteSelectionIndex >= 0 && autocompleteSelectionIndex < autocompleteOptions.size()) {
                        String selection = autocompleteOptions.get(autocompleteSelectionIndex);
                        input.clear();
                        input.insert(selection + " ");
                        updateAutocompleteState();
                        redrawInputBox();
                        return;
                    }
                }
                case ENTER -> {
                    // Submit leaf commands immediately; expand multi-word commands into buffer
                    if (autocompleteSelectionIndex >= 0 && autocompleteSelectionIndex < autocompleteOptions.size()) {
                        String selection = autocompleteOptions.get(autocompleteSelectionIndex);
                        autocompleteActive = false;
                        input.clear();
                        input.insert(selection);
                        redrawInputBox();
                        submit(); // submit immediately without trailing space
                        return;
                    }
                }
                case ESCAPE -> {
                    autocompleteActive = false;
                    redrawInputBox();
                    return;
                }
                default -> {}
            }
        }

        switch (key.kind()) {
            case CTRL_C -> {
                if (input.isEmpty()) { running = false; }
                else { input.clear(); updateAutocompleteState(); redrawInputBox(); }
            }
            case ESCAPE -> {
                // Clear the input buffer (same as Ctrl+C when text is present),
                // or do nothing if the buffer is already empty.
                if (!input.isEmpty()) {
                    input.clear();
                    updateAutocompleteState();
                    redrawInputBox();
                }
            }
            case CTRL_D -> { if (input.isEmpty()) running = false; }
            case CTRL_L -> { fullRedraw(); }
            case ENTER -> submit();
            default -> {
                if (input.apply(key)) {
                    updateAutocompleteState();
                    redrawInputBox();
                }
            }
        }
    }

    private void updateAutocompleteState() {
        String text = input.text();
        if (text.startsWith("/")) {
            String typed = text.toLowerCase();
            autocompleteOptions.clear();
            for (String cmd : KNOWN_COMMANDS) {
                if (cmd.startsWith(typed)) {
                    autocompleteOptions.add(cmd);
                }
            }
            autocompleteActive = !autocompleteOptions.isEmpty() && !autocompleteOptions.contains(text.trim());
            if (autocompleteSelectionIndex >= autocompleteOptions.size()) {
                autocompleteSelectionIndex = 0;
            }
        } else {
            autocompleteActive = false;
        }
    }

    private void submit() {
        String text = input.text().strip();
        input.clear();
        if (text.isEmpty()) { redrawInputBox(); return; }

        if (expectingModelSelection && text.matches("\\d+")) {
            text = "/models " + text;
        }
        expectingModelSelection = false;
        
        final String submittedText = text;

        if (submittedText.startsWith("/")) {
            redrawInputBox();
            handleSlashInteractive(submittedText);
            return;
        }

        appendBlock(userEchoLines(submittedText));
        streaming = true;
        redrawInputBox();

        AndalusAgentListener listener = new InteractiveListener();
        agentThread = new Thread(() -> {
            try {
                agent.send(submittedText, listener);
            } catch (Throwable t) {
                appendBlock(List.of(
                    Ansi.fg(Theme.ERROR) + "Agent error: " + t.getMessage() + Ansi.RESET
                ));
            } finally {
                streaming = false;
                redrawInputBox();
            }
        }, "agent-turn");
        agentThread.setDaemon(true);
        agentThread.start();
    }

    private void handleSlashInteractive(String cmd) {
        String[] parts = cmd.split("\\s+", 2);
        switch (parts[0]) {
            case "/quit", "/exit" -> running = false;
            case "/help" -> appendBlock(helpLines());
            case "/clear" -> { agent.clearHistory(); fullRedraw(); }
            case "/panel" -> { exitAction = "panel"; running = false; }
            case "/coder" -> {
                appendBlock(List.of(
                    Ansi.fg(Theme.TOOL_OK) + "Switching to TDD Coder Orchestrator..." + Ansi.RESET,
                    Ansi.fg(Theme.WARN) + "Active provider is now TDD Coder (via REST API)." + Ansi.RESET
                ));
                exitAction = "provider:aqli-engine";
                running = false;
            }
            case "/tools" -> {
                List<String> lines = new ArrayList<>();
                lines.add("");
                lines.add(Ansi.BOLD + "Available Tools & Skills" + Ansi.RESET);
                if (agent == null || agent.tools() == null || agent.tools().isEmpty()) {
                    lines.add(Ansi.fg(Theme.WARN) + "  No tools available." + Ansi.RESET);
                } else {
                    for (tech.kayys.andalus.tools.spi.Tool t : agent.tools()) {
                        lines.add("  " + Ansi.fg(Theme.TOOL_OK) + t.name() + Ansi.RESET
                                + Ansi.dim("  " + (t.description() != null ? t.description() : "")));
                    }
                }
                lines.add("");
                appendBlock(lines);
            }
            case "/models" -> {
                if (parts.length > 1 && !parts[1].isBlank()) {
                    String arg = parts[1].trim();
                    if (arg.startsWith("pull ")) {
                        String modelSpec = arg.substring(5).trim();
                        if (modelManager != null) {
                            synchronized (termLock) {
                                out.moveTo(scrollBottom, 1);
                                out.line("");
                                out.flush();
                                System.out.println();
                                modelManager.pullModel(System.out, modelSpec);
                            }
                            fullRedraw();
                        } else {
                            appendBlock(List.of(Ansi.fg(Theme.WARN) + "Model pulling not supported." + Ansi.RESET));
                        }
                    } else {
                        String modelId = arg.startsWith("set ") ? arg.substring(4).trim() : arg;
                        if (modelId.matches("\\d+") && modelManager != null) {
                            int idx = Integer.parseInt(modelId) - 1;
                            List<ModelManager.ModelRow> models = modelManager.listModels();
                            if (idx >= 0 && idx < models.size()) {
                                modelId = models.get(idx).shortId();
                            }
                        }
                        if (agent != null) {
                            agent.setModelId(modelId);
                            if (config != null && config.activeProfile() != null) {
                                config.activeProfile().model = modelId;
                                try { config.save(Config.defaultConfigPath()); } catch (Exception ignored) {}
                            }
                            appendBlock(List.of(Ansi.fg(Theme.TOOL_OK) + "Model assigned: " + modelId + Ansi.RESET));
                            fullRedraw();
                        }
                    }
                } else {
                    if (modelManager != null) {
                        List<ModelManager.ModelRow> models = modelManager.listModels();
                        if (models.isEmpty()) {
                            appendBlock(List.of(Ansi.fg(Theme.WARN) + "  No models found." + Ansi.RESET + Ansi.dim("  Run: /models pull <model>")));
                        } else {
                            try {
                                List<GenericPickerWidget.PickerItem> items = models.stream()
                                    .map(m -> new GenericPickerWidget.PickerItem(m.shortId(), m.shortId(), m.name(), m.format(), m.sizeStr()))
                                    .toList();
                                GenericPickerWidget picker = new GenericPickerWidget(out, keys, "Available Models", items, cols, rows);
                                String selected = picker.showAndSelect();
                                if (selected != null) {
                                    this.exitAction = "model:" + selected;
                                    this.running = false;
                                } else {
                                    appendBlock(List.of(Ansi.fg(Theme.WARN) + "Model selection canceled." + Ansi.RESET));
                                }
                            } catch (IOException e) {
                                appendBlock(List.of(Ansi.fg(Theme.ERROR) + "Error rendering picker: " + e.getMessage() + Ansi.RESET));
                            }
                        }
                    } else {
                        appendBlock(List.of(Ansi.fg(Theme.WARN) + "Model list not available in this mode." + Ansi.RESET));
                    }
                }
            }
            case "/providers" -> {
                if (providerManager != null) {
                    List<ProviderManager.ProviderRow> providers = providerManager.listProviders();
                    try {
                        ProviderPickerWidget picker = new ProviderPickerWidget(out, keys, providers, cols, rows);
                        String selected = picker.showAndSelect();
                        if (selected != null) {
                            this.exitAction = "provider:" + selected;
                            this.running = false;
                        } else {
                            appendBlock(List.of(Ansi.fg(Theme.WARN) + "Provider selection canceled." + Ansi.RESET));
                        }
                    } catch (IOException e) {
                        appendBlock(List.of(Ansi.fg(Theme.ERROR) + "Error rendering picker: " + e.getMessage() + Ansi.RESET));
                    }
                } else {
                    appendBlock(List.of(Ansi.fg(Theme.WARN) + "Provider list not available." + Ansi.RESET));
                }
            }
            case "/provider" -> {
                if (parts.length > 1) {
                    String providerId = parts[1].trim();
                    this.exitAction = "provider:" + providerId;
                    this.running = false; // exit ReplUi loop to recreate agent
                } else {
                    if (providerManager != null) {
                        List<ProviderManager.ProviderRow> providers = providerManager.listProviders();
                        try {
                            ProviderPickerWidget picker = new ProviderPickerWidget(out, keys, providers, cols, rows);
                            String selected = picker.showAndSelect();
                            if (selected != null) {
                                this.exitAction = "provider:" + selected;
                                this.running = false;
                            } else {
                                appendBlock(List.of(Ansi.fg(Theme.WARN) + "Provider selection canceled." + Ansi.RESET));
                            }
                        } catch (IOException e) {
                            appendBlock(List.of(Ansi.fg(Theme.ERROR) + "Error rendering picker: " + e.getMessage() + Ansi.RESET));
                        }
                    } else {
                        appendBlock(List.of(Ansi.fg(Theme.WARN) + "Usage: /provider <id>" + Ansi.RESET));
                    }
                }
            }
            default -> {
                if (pickerCallbacks.containsKey(parts[0])) {
                    PickerCallback cb = pickerCallbacks.get(parts[0]);
                    try {
                        GenericPickerWidget picker = new GenericPickerWidget(out, keys, parts[0], cb.getItems(), cols, rows);
                        String selected = picker.showAndSelect();
                        if (selected != null) {
                            cb.onSelect(selected);
                        } else {
                            appendBlock(List.of(Ansi.fg(Theme.WARN) + parts[0] + " selection canceled." + Ansi.RESET));
                        }
                    } catch (IOException e) {
                        appendBlock(List.of(Ansi.fg(Theme.ERROR) + "Error rendering picker: " + e.getMessage() + Ansi.RESET));
                    }
                    return;
                }

                if (multiPickerCallbacks.containsKey(parts[0])) {
                    MultiPickerCallback cb = multiPickerCallbacks.get(parts[0]);
                    try {
                        GenericPickerWidget picker = new GenericPickerWidget(out, keys, parts[0], cb.getItems(), cols, rows);
                        List<String> selected = picker.showAndSelectMultiple();
                        if (selected != null) {
                            cb.onSelect(selected);
                        } else {
                            appendBlock(List.of(Ansi.fg(Theme.WARN) + parts[0] + " selection canceled." + Ansi.RESET));
                        }
                    } catch (IOException e) {
                        appendBlock(List.of(Ansi.fg(Theme.ERROR) + "Error rendering picker: " + e.getMessage() + Ansi.RESET));
                    }
                    return;
                }
                
                if (externalSlashHandler != null) {
                    Boolean shouldExit = externalSlashHandler.apply(cmd);
                    if (Boolean.TRUE.equals(shouldExit)) {
                        running = false;
                    }
                } else {
                    appendBlock(List.of(
                        Ansi.fg(Theme.ERROR) + "Unknown command: " + parts[0] + Ansi.RESET
                        + Ansi.dim("  (try /help)")));
                }
            }
        }
    }

    // ---------- transcript rendering (interactive mode) ----------

    private void appendBlock(List<String> lines) {
        synchronized (termLock) {
            recomputeLayoutIfNeeded();
            out.moveTo(scrollBottom, 1);
            for (String l : lines) {
                if (l.isEmpty()) { out.line(""); continue; }
                for (String wrapped : TextWrap.wrap(l, Math.max(10, cols))) out.line(wrapped);
            }
            out.flush();
        }
    }

    private String getWorkspacePrefix() {
        if (agent == null || agent.workspace() == null) return "";
        return "[" + agent.workspace().getFileName().toString() + "] ";
    }

    private List<String> userEchoLines(String text) {
        String wp = getWorkspacePrefix();
        String styled = Ansi.BOLD + Ansi.fg(Theme.USER) + wp + "› " + Ansi.RESET + Ansi.fg(Theme.USER) + text + Ansi.RESET;
        List<String> result = new ArrayList<>();
        result.add("");
        result.add(styled);
        return result;
    }

    private List<String> helpLines() {
        List<String> l = new ArrayList<>();
        l.add("");
        l.add(Ansi.BOLD + "Commands" + Ansi.RESET);
        l.add("  " + Ansi.BOLD + "/models" + Ansi.RESET + "     List available models");
        l.add("  " + Ansi.BOLD + "/models <id>" + Ansi.RESET + "Assign an active model");
        l.add("  " + Ansi.BOLD + "/models pull <id>" + Ansi.RESET + " Pull a model from HuggingFace/Ollama");
        l.add("  /help            show this help");
        l.add("  /clear           clear conversation history");
        l.add("  /panel           switch to panel UI mode");
        l.add("  /quit, /exit     exit");
        l.add("");
        l.add(Ansi.BOLD + "Skills & Models" + Ansi.RESET);
        l.add("  /skills          List available agent skills");
        l.add("");
        l.add(Ansi.BOLD + "Sessions & Projects" + Ansi.RESET);
        l.add("  /sessions                   List sessions for current project");
        l.add("  /sessions resume <id>       Resume a saved session");
        l.add("  /sessions fork <id> [name]  Fork a session into a new branch");
        l.add("  /sessions delete <id>       Delete a stored session");
        l.add("  /projects                   List known projects");
        l.add("  /project <id>               Switch to a different project");
        l.add("");
        l.add(Ansi.BOLD + "Status & Info" + Ansi.RESET);
        l.add("  /status          Show platform adapter status");
        l.add("  /info            Display system info");
        l.add("  /tools           List all available tools");
        l.add("");
        l.add(Ansi.BOLD + "Keys" + Ansi.RESET);
        l.add("  Enter            send message");
        l.add("  Shift/Alt+Enter  insert newline");
        l.add("  Esc              cancel/interrupt running response, or clear input");
        l.add("  Ctrl+C           clear input, or exit (when input empty)");
        l.add("  Ctrl+L           redraw screen");
        l.add("");
        return l;
    }

    /**
     * Returns the banner lines displayed at startup.
     * Shows "No model selected" in amber when the active profile has no model configured.
     */
    private List<String> bannerLines() {
        List<String> l = new ArrayList<>();
        String title = Ansi.BOLD + Ansi.fg(Theme.ACCENT) + "Andalus Code" + Ansi.RESET;

        // Resolve model for display -- show a clear "No model selected" when blank
        String curModel = null;
        String curProvider = null;
        try {
            Config.Profile profile = config != null ? config.activeProfile() : null;
            if (profile != null) {
                curModel = profile.model;
                curProvider = profile.provider;
            }
        } catch (Exception ignored) {}

        String modelDisplay = (curModel == null || curModel.isBlank())
                ? Ansi.fg(Theme.WARN) + "No model selected" + Ansi.RESET
                        + Ansi.dim("  (use /models or --model <id>)")
                : curModel;

        String providerDisplay = (curProvider != null && !curProvider.isBlank()) ? curProvider : "aqli (default)";
        l.add(title + Ansi.dim("  — general-purpose AI assistant  ·  Model: ") + modelDisplay + Ansi.dim("  ·  Provider: ") + providerDisplay);
        l.add(Ansi.dim("  Type your message and press Enter. /help for commands."));
        l.add("");
        return l;
    }

    private String bannerPlain() {
        return "Andalus Agent — general-purpose AI assistant";
    }

    private String summarizeArgs(Object input) {
        String s = input != null ? input.toString() : "{}";
        if (s.length() > 140) s = s.substring(0, 140) + "...";
        return s;
    }

    private String firstLine(String s) {
        if (s == null) return "";
        int idx = s.indexOf('\n');
        String first = idx == -1 ? s : s.substring(0, idx);
        if (first.length() > 160) first = first.substring(0, 160) + "...";
        return first + (idx != -1 ? "  (+more)" : "");
    }

    private String stripAnsiForPlain(String s) { return Ansi.strip(s); }

    // ---------- input box rendering ----------

    private void recomputeLayoutIfNeeded() {
        int newCols = polledCols;
        int newRows = polledRows;
        int autocompleteHeight = autocompleteActive ? Math.min(5, autocompleteOptions.size()) + 1 : 0; // +1 for a border/header if we want, or just the list
        int newInputHeight = Math.max(2, input.lineCount() + 1) + autocompleteHeight;
        int newScrollBottom = Math.max(1, newRows - newInputHeight);

        if (scrollBottom == 0) {
            cols = newCols; rows = newRows; inputHeight = newInputHeight; scrollBottom = newScrollBottom;
            out.setScrollRegion(1, scrollBottom);
            return;
        }

        boolean terminalResized = newCols != cols || newRows != rows;
        boolean boxHeightChanged = newScrollBottom != scrollBottom;

        if (terminalResized) {
            cols = newCols; rows = newRows; inputHeight = newInputHeight; scrollBottom = newScrollBottom;
            out.resetScrollRegion();
            out.write(Ansi.CLEAR_SCREEN);
            out.write(Ansi.CURSOR_HOME);
            out.setScrollRegion(1, scrollBottom);
            out.moveTo(scrollBottom, 1);
            out.line(Ansi.dim("── terminal resized to " + cols + "x" + rows + " ──"));
            return;
        }

        if (boxHeightChanged) {
            if (newScrollBottom > scrollBottom) {
                for (int r = scrollBottom + 1; r <= Math.min(newScrollBottom, rows); r++) {
                    out.moveTo(r, 1);
                    out.clearLine();
                }
            }
            inputHeight = newInputHeight; scrollBottom = newScrollBottom;
            out.setScrollRegion(1, scrollBottom);
        }
    }

    private void redrawInputBox() {
        synchronized (termLock) {
            recomputeLayoutIfNeeded();

            String fullText = input.text();
            int cursorPos = input.cursor();
            int cursorLineIdx = countNewlinesBefore(fullText, cursorPos);
            int lineStartIdx = fullText.lastIndexOf('\n', Math.max(0, cursorPos - 1)) + 1;
            int cursorCol = cursorPos - lineStartIdx;

            String[] lines = input.lines();
            String wp = getWorkspacePrefix();
            int promptWidth = wp.length() + 2; // wp length + "› "
            int availWidth = Math.max(4, cols - promptWidth);
            int cursorVisCol = promptWidth + 1;
            
            int autocompleteHeight = autocompleteActive ? Math.min(5, autocompleteOptions.size()) : 0;
            
            // Draw autocomplete if active
            if (autocompleteActive) {
                int startRow = scrollBottom + 1;
                for (int i = 0; i < autocompleteHeight; i++) {
                    out.moveTo(startRow + i, 1);
                    out.clearLine();
                    if (i < autocompleteOptions.size()) {
                        String opt = autocompleteOptions.get(i);
                        if (i == autocompleteSelectionIndex) {
                            out.write(Ansi.fg("#00ff00") + " > " + opt + Ansi.RESET);
                        } else {
                            out.write("   " + Ansi.fg(Theme.DIM) + opt + Ansi.RESET);
                        }
                    }
                }
            }

            for (int i = 0; i < lines.length; i++) {
                int row = scrollBottom + 1 + autocompleteHeight + i;
                out.moveTo(row, 1);
                out.clearLine();
                String marker = i == 0
                        ? Ansi.fg(Theme.ACCENT) + (streaming ? "⚙ " + " ".repeat(Math.max(0, promptWidth - 2)) : wp + "› ") + Ansi.RESET
                        : " ".repeat(promptWidth);
                String lineText = lines[i];
                String display;
                int startOffset = 0;
                if (i == cursorLineIdx) {
                    startOffset = Math.max(0, cursorCol - availWidth + 1);
                    int end = Math.min(lineText.length(), startOffset + availWidth);
                    display = lineText.substring(Math.min(startOffset, lineText.length()), end);
                    cursorVisCol = promptWidth + (cursorCol - startOffset) + 1;
                } else if (lineText.length() > availWidth) {
                    display = lineText.substring(0, Math.max(0, availWidth - 1)) + "…";
                } else {
                    display = lineText;
                }
                out.write(marker + (streaming ? Ansi.dim(display) : display));
            }

            int hintRow = scrollBottom + lines.length + 1 + autocompleteHeight;
            out.moveTo(hintRow, 1);
            out.clearLine();
            String hint = streaming
                    ? Ansi.fg(Theme.DIM) + "Working…  Esc or Ctrl+C to interrupt" + Ansi.RESET
                    : Ansi.fg(Theme.DIM) + "Enter to send · Shift+Enter newline · Esc clear input · /help · Ctrl+C exit" + Ansi.RESET;
            out.write(TextWrap.truncate(hint, cols));

            int cursorRow = scrollBottom + 1 + autocompleteHeight + cursorLineIdx;
            out.moveTo(cursorRow, cursorVisCol);
            out.flush();
        }
    }

    private int countNewlinesBefore(String s, int pos) {
        int count = 0;
        for (int i = 0; i < pos && i < s.length(); i++) if (s.charAt(i) == '\n') count++;
        return count;
    }

    private void fullRedraw() {
        synchronized (termLock) {
            out.resetScrollRegion();
            out.write(Ansi.CLEAR_SCREEN);
            out.write(Ansi.CURSOR_HOME);
            scrollBottom = 0;
            recomputeLayoutIfNeeded();
            appendBlock(bannerLines());
            redrawInputBox();
        }
    }

    // ---------- listener used while streaming in interactive mode ----------

    private final class InteractiveListener implements AndalusAgentListener {
        private boolean streamOpen = false;
        // Thinking-stream state
        private boolean inThinkingSection = false;
        private boolean thinkingLineOpen  = false;
        private int     thinkingLineCount = 0;
        private StringBuilder thinkingBuffer = new StringBuilder();

        /** Regex matching LLM planning/summary section headers. */
        private static final java.util.regex.Pattern THINKING_PATTERN =
            java.util.regex.Pattern.compile(
                "^\\*\\*(Plan|Summary|Findings?|Next|Tests?(&\\s*Verification)?|" +
                "Verification|Answer|Result|Analysis|Research|Approach|Strategy|Steps?)\\**",
                java.util.regex.Pattern.CASE_INSENSITIVE);

        private void ensureOpen() {
            if (!streamOpen) {
                synchronized (termLock) {
                    recomputeLayoutIfNeeded();
                    out.moveTo(scrollBottom, 1);
                    out.write(Ansi.fg(Theme.ASSISTANT));
                    out.flush();
                }
                streamOpen = true;
            }
        }

        private void closeIfOpen() {
            if (streamOpen) {
                synchronized (termLock) {
                    out.write(Ansi.RESET);
                    out.write("\r\n\r\n");
                    out.flush();
                }
                streamOpen = false;
            }
        }

        @Override public void onThinkingDelta(String text) {
            // Re-use the existing logic to format it beautifully as a thinking stream.
            // We just feed it to thinkingBuffer and process it without needing THINKING_PATTERN matching,
            // because we know it's a thought.
            thinkingBuffer.append(text);
            String buf = thinkingBuffer.toString();

            int lastNewline = buf.lastIndexOf('\n');
            if (lastNewline < 0 && buf.length() < 4096) {
                synchronized (termLock) {
                    recomputeLayoutIfNeeded();
                    if (!thinkingLineOpen) {
                        out.moveTo(scrollBottom, 1);
                        out.write(Ansi.fg(Theme.THINKING) + Ansi.dim(""));
                        thinkingLineOpen = true;
                    }
                    out.write(text.replace("\r\n", "\n").replace("\n", "\r\n"));
                    out.flush();
                }
                return;
            }

            String toProcess = lastNewline >= 0 ? buf.substring(0, lastNewline + 1) : buf;
            String remainder = lastNewline >= 0 ? buf.substring(lastNewline + 1) : "";
            thinkingBuffer = new StringBuilder(remainder);

            for (String line : toProcess.split("\n", -1)) {
                if (!inThinkingSection) {
                    closeIfOpen();
                    inThinkingSection = true;
                    thinkingLineCount = 0;
                    thinkingLineOpen = false;
                }

                synchronized (termLock) {
                    recomputeLayoutIfNeeded();
                    out.moveTo(scrollBottom, 1);
                    if (!thinkingLineOpen) {
                        out.write(Ansi.fg(Theme.THINKING) + Ansi.dim(""));
                        thinkingLineOpen = true;
                    }
                    out.write("\r\033[2K");
                    String display = line.isEmpty() ? "" :
                            Ansi.fg(Theme.THINKING) + "  ⟳ " + truncateThinking(line) + Ansi.RESET;
                    out.write(display);
                    out.flush();
                }
                if (!line.isBlank()) thinkingLineCount++;
            }
        }

        @Override public void onThinkingEnd() {
            if (inThinkingSection) {
                collapseThinkingSection();
                inThinkingSection = false;
            }
        }

        @Override public void onTextDelta(String t) {
            // Since AndalusProvider correctly separates Thoughts into onThinkingDelta,
            // anything arriving here is pure text.
            ensureOpen();
            synchronized (termLock) {
                out.write(t.replace("\r\n", "\n").replace("\n", "\r\n"));
                out.flush();
            }
        }


        private void collapseThinkingSection() {
            synchronized (termLock) {
                // Erase the thinking line and replace with a collapsed indicator
                out.write("\r\033[2K"); // clear line
                out.write(Ansi.fg(Theme.DIM) + "  ✦ thinking..." + Ansi.RESET + "\r\n");
                out.flush();
            }
            thinkingLineOpen = false;
            thinkingLineCount = 0;
        }

        private String truncateThinking(String line) {
            String s = line.replaceAll("\\*\\*([^*]+)\\*\\*", "$1") // strip bold markers
                           .replaceAll("[#*`]", "").strip();
            return s.length() > Math.max(20, cols - 8) ? s.substring(0, cols - 8) + "…" : s;
        }

        @Override public void onToolCallStart(String id, String name) {
            closeIfOpen();
            appendBlock(List.of(Ansi.fg(Theme.TOOL) + "→ " + name + Ansi.RESET));
        }

        @Override public void onToolCallReady(String id, String name, java.util.Map<String, Object> toolInput) {
            // Args are hidden by default to keep the UX clean.
            // Set env ANDALUS_VERBOSE_TOOLS=1 to show them.
            if ("1".equals(System.getenv("ANDALUS_VERBOSE_TOOLS"))
                    || Boolean.getBoolean("andalus.verbose.tools")) {
                appendBlock(List.of(Ansi.dim("  " + summarizeArgs(toolInput))));
            }
        }

        @Override public void onToolPermissionNeeded(String id, String name, java.util.Map<String, Object> toolInput,
                                                     Consumer<PermissionDecision> responder) {
            pendingPermissionResponder = responder;
            pendingToolName = name;
            appendBlock(List.of(
                    Ansi.fg(Theme.TOOL) + "  Allow '" + name + "' to run?" + Ansi.RESET
                    + Ansi.dim("  [y]es  [a]lways  [n]o")
            ));
            redrawInputBox();
        }

        @Override public void onToolResult(String id, String name, ToolResult result) {
            String marker = !result.success()
                    ? Ansi.fg(Theme.ERROR) + "✗ "
                    : Ansi.fg(Theme.TOOL_OK) + "✓ ";
            String out = result.output().orElse(result.error() != null ? result.error() : "");
            List<String> block = new ArrayList<>();
            block.add(marker + name + Ansi.RESET);
            if (!out.isBlank()) {
                String[] lines = out.split("\n", -1);
                int max = Math.min(lines.length, 12);
                for (int i = 0; i < max; i++) {
                    String l = lines[i];
                    if (l.length() > cols - 6) l = l.substring(0, Math.max(4, cols - 9)) + "...";
                    block.add(Ansi.dim("  ") + l);
                }
                if (lines.length > max) {
                    block.add(Ansi.dim("  (" + (lines.length - max) + " more lines)"));
                }
            }
            appendBlock(block);
        }

        @Override public void onUsage(int inTok, int outTok) { /* could surface in hint line later */ }

        @Override public void onDone(String reason) {
            closeIfOpen();
        }

        @Override public void onError(String message) {
            closeIfOpen();
            appendBlock(List.of(Ansi.fg(Theme.ERROR) + "Error: " + message + Ansi.RESET));
        }
    }
}
