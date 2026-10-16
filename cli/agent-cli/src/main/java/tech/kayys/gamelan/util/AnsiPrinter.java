package tech.kayys.gamelan.util;

import tech.kayys.gamelan.agent.AgentResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * ANSI-aware terminal printer with color, formatting, and UX helpers.
 *
 * <p>When color is disabled, all ANSI codes are stripped and plain text is used.
 */
public class AnsiPrinter {

    private final boolean colorEnabled;

    // ANSI color codes
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String CYAN = "\u001B[36m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String WHITE = "\u001B[37m";

    public AnsiPrinter(boolean colorEnabled) {
        this.colorEnabled = colorEnabled;
    }

    public void println() {
        System.out.println();
    }

    public void println(String text) {
        System.out.println(applyFormatting(text));
    }

    public void print(String text) {
        System.out.print(applyFormatting(text));
    }

    public void info(String text) {
        println(styled(BLUE, "i") + " " + text);
    }

    public void success(String text) {
        println(styled(GREEN, "✓") + " " + text);
    }

    public void warn(String text) {
        println(styled(YELLOW, "⚠") + " " + text);
    }

    public void error(String text) {
        System.err.println(styled(RED, "✗") + " " + text);
    }

    public void sectionHeader(String text) {
        println("\n" + BOLD + CYAN + text + RESET);
    }

    /** Alias for sectionHeader with optional count suffix. */
    public void header(String text) {
        sectionHeader(text);
    }

    public void header(String text, Object detail) {
        sectionHeader(text + " — " + detail);
    }

    public void section(String text) {
        println(BOLD + text + RESET);
    }

    public void listItem(String name, String description) {
        println("  " + styled(GREEN, name) + (description != null && !description.isBlank()
                ? DIM + " — " + description + RESET
                : ""));
    }

    public void toolCall(String toolName, String args) {
        println("\n  " + styled(MAGENTA, "⚡") + " [" + styled(CYAN, toolName) + "]");
        if (args != null && !args.isBlank()) {
            println("    " + DIM + args + RESET);
        }
    }

    public void toolResult(String toolName, int outputLength, Duration duration) {
        println("  " + styled(GREEN, "✓") + " [" + toolName + "] " +
                DIM + outputLength + " chars in " + duration.toMillis() + "ms" + RESET);
    }

    public void agentResponse(AgentResponse response, boolean streaming) {
        if (response == null) {
            warn("No response from agent");
            return;
        }

        if (response.hasError()) {
            error("Agent error: " + response.text());
            return;
        }

        if (!streaming && response.text() != null && !response.text().isBlank()) {
            println("\n" + BOLD + CYAN + "Gamelan" + RESET + "\n");
            println(response.text());
        }

        // Show tool results summary
        if (response.toolResults() != null && !response.toolResults().isEmpty()) {
            println("\n" + DIM + "Tools used: " + response.toolResults().size() + RESET);
        }

        // Show skills used
        if (response.skillsUsed() != null && !response.skillsUsed().isEmpty()) {
            println(DIM + "Skills: " + String.join(", ", response.skillsUsed()) + RESET);
        }
    }

    public void banner() {
        println(BOLD + CYAN + "  Gamelan CLI" + RESET + DIM + " v1.0.0" + RESET);
        println(DIM + "  Powered by Aqli inference engine + agentskills.io" + RESET);
        println(DIM + "  Type /help for available commands" + RESET);
        println(DIM + "  ────────────────────────────────────────" + RESET);
    }

    public String prompt(tech.kayys.gamelan.session.ConversationSession session) {
        String turnInfo = session != null ? DIM + "[" + session.turnCount() + "]" + RESET + " " : "";
        return turnInfo + styled(CYAN, "❯") + " ";
    }

    public void tokenUsage(int used, int total) {
        double pct = total > 0 ? (double) used / total * 100 : 0;
        String bar = buildProgressBar(pct, 30);
        String color = pct > 90 ? RED : pct > 70 ? YELLOW : GREEN;
        println(DIM + "  Context: " + bar + " " + color + used + "/" + total + " tokens (" + String.format("%.0f%%", pct) + ")" + RESET);
    }

    public void sessionInfo(String id, int turns, Duration duration, int tokenCount) {
        sectionHeader("Session Info");
        println("  ID       : " + id);
        println("  Turns    : " + turns);
        println("  Tokens   : " + tokenCount);
        println("  Duration : " + formatDuration(duration));
    }

    public void costEstimate(int inputTokens, int outputTokens, double pricePerM) {
        double inputCost = inputTokens * pricePerM / 1_000_000;
        double outputCost = outputTokens * pricePerM / 1_000_000;
        sectionHeader("Token Usage & Cost Estimate");
        println("  Input tokens  : " + inputTokens);
        println("  Output tokens : " + outputTokens);
        println("  Total tokens  : " + (inputTokens + outputTokens));
        if (pricePerM > 0) {
            println(DIM + "  Estimated cost: $" + String.format("%.4f", inputCost + outputCost) +
                    " (at $" + pricePerM + "/M tokens)" + RESET);
        }
    }

    public void permissions(List<String> trustedTools, List<String> blockedTools) {
        sectionHeader("Tool Permissions");
        if (!trustedTools.isEmpty()) {
            println("  " + styled(GREEN, "Trusted:") + " " + String.join(", ", trustedTools));
        }
        if (!blockedTools.isEmpty()) {
            println("  " + styled(RED, "Blocked:") + " " + String.join(", ", blockedTools));
        }
        if (trustedTools.isEmpty() && blockedTools.isEmpty()) {
            println(DIM + "  No tool permissions configured" + RESET);
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private String applyFormatting(String text) {
        if (!colorEnabled) {
            // Strip ANSI codes
            return text.replaceAll("\u001B\\[[;\\d]*[a-zA-Z]", "");
        }
        return text;
    }

    private String styled(String color, String text) {
        if (!colorEnabled) return text;
        return color + text + RESET;
    }

    private String buildProgressBar(double percent, int width) {
        int filled = (int) (percent / 100.0 * width);
        int empty = width - filled;
        String color = percent > 90 ? RED : percent > 70 ? YELLOW : GREEN;
        return color + "█".repeat(filled) + DIM + "░".repeat(empty) + RESET;
    }

    private String formatDuration(Duration d) {
        if (d.toHours() > 0) {
            return String.format("%dh %dm %ds", d.toHours(), d.toMinutesPart(), d.toSecondsPart());
        } else if (d.toMinutes() > 0) {
            return String.format("%dm %ds", d.toMinutesPart(), d.toSecondsPart());
        } else {
            return d.toSeconds() + "s";
        }
    }
}
