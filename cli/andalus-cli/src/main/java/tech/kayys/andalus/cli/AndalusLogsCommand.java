package tech.kayys.andalus.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code andalus logs} — view or tail Andalus session logs stored in
 * {@code ~/.andalus/logs/}.
 *
 * <p>Examples:
 * <pre>
 *   andalus logs                 # last 40 lines of andalus.log
 *   andalus logs -n 100          # last 100 lines
 *   andalus logs -f              # follow (tail -f style)
 *   andalus logs --list          # list all log files in ~/.andalus/logs/
 *   andalus logs --file andalus.log.1   # view a specific log file
 * </pre>
 */
@Command(
        name = "logs",
        description = "View or tail Andalus session logs (~/.andalus/logs/).",
        mixinStandardHelpOptions = true)
final class AndalusLogsCommand implements Callable<Integer> {

    // ANSI
    private static final String RESET  = "\u001B[0m";
    private static final String BOLD   = "\u001B[1m";
    private static final String DIM    = "\u001B[2m";
    private static final String CYAN   = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String GREEN  = "\u001B[32m";
    private static final String RED    = "\u001B[31m";

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @ParentCommand
    AndalusAqliCli parent;

    @Option(names = {"-n", "--lines"},
            description = "Number of lines to show (default: 40).",
            defaultValue = "40")
    int lines;

    @Option(names = {"-f", "--follow"},
            description = "Follow the log file (like tail -f). Press Ctrl-C to stop.")
    boolean follow;

    @Option(names = {"--list"},
            description = "List all log files in ~/.andalus/logs/.")
    boolean list;

    @Option(names = {"--file"},
            description = "Log file name inside ~/.andalus/logs/ to view (default: andalus.log).",
            defaultValue = "andalus.log")
    String fileName;

    @Option(names = {"--no-color"},
            description = "Disable ANSI colour.")
    boolean noColor;

    @Override
    public Integer call() {
        PrintStream out = parent.context().out();
        boolean color = !noColor && isColorSupported();

        if (list) {
            return listLogFiles(out, color);
        }
        return showLog(out, color);
    }

    // ── list ────────────────────────────────────────────────────────────────

    private int listLogFiles(PrintStream out, boolean color) {
        Path dir = AndalusLogging.LOGS_DIR;
        if (!Files.isDirectory(dir)) {
            out.println(colorize(color, YELLOW, "  No logs directory found: " + dir));
            out.println(colorize(color, DIM,    "  Logs are created when you first run 'andalus agent'."));
            return 0;
        }

        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> logFiles = stream
                    .filter(p -> p.getFileName().toString().startsWith("andalus"))
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .collect(Collectors.toList());

            if (logFiles.isEmpty()) {
                out.println(colorize(color, YELLOW, "  No log files found in " + dir));
                return 0;
            }

            out.println();
            out.printf(colorize(color, BOLD, "  %-30s %-12s %-20s%n"),
                    "FILE", "SIZE", "LAST MODIFIED");
            out.println(colorize(color, DIM, "  " + "─".repeat(64)));

            for (Path p : logFiles) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                    long sizeKb = attrs.size() / 1024;
                    LocalDateTime modified = LocalDateTime.ofInstant(
                            attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault());
                    String name = p.getFileName().toString();
                    boolean isCurrent = name.equals(fileName);
                    String nameStr = isCurrent
                            ? colorize(color, GREEN + BOLD, name)
                            : colorize(color, CYAN, name);
                    out.printf("  %-30s %-12s %-20s%n",
                            nameStr,
                            sizeKb + " KB",
                            TS_FMT.format(modified));
                } catch (IOException e) {
                    out.printf("  %-30s %-12s%n", p.getFileName(), "?");
                }
            }
            out.println();
            out.println(colorize(color, DIM,
                    "  Use 'andalus logs --file <name>' to view a specific file."));
            out.println();
        } catch (IOException e) {
            out.println(colorize(color, RED, "  Error listing logs: " + e.getMessage()));
            return 1;
        }
        return 0;
    }

    // ── show / tail ─────────────────────────────────────────────────────────

    private int showLog(PrintStream out, boolean color) {
        Path logFile = AndalusLogging.LOGS_DIR.resolve(fileName);

        if (!Files.exists(logFile)) {
            try {
                Files.createDirectories(logFile.getParent());
                Files.createFile(logFile);
            } catch (IOException e) {
                out.println(colorize(color, RED, "  Failed to create log file: " + e.getMessage()));
                return 1;
            }
        }

        printHeader(out, color, logFile);

        try {
            // Print last N lines
            printLastLines(out, color, logFile, lines);

            if (follow) {
                out.println(colorize(color, DIM,
                        "\n  --- following " + logFile.getFileName() + " (Ctrl-C to stop) ---"));
                tailFollow(out, logFile);
            }
        } catch (IOException e) {
            out.println(colorize(color, RED, "  Error reading log: " + e.getMessage()));
            return 1;
        }
        return 0;
    }

    private void printHeader(PrintStream out, boolean color, Path logFile) {
        out.println();
        out.println(colorize(color, BOLD + CYAN, "  andalus logs") +
                colorize(color, DIM, "  ·  " + logFile.toAbsolutePath()));
        try {
            long size = Files.size(logFile);
            out.println(colorize(color, DIM, "  size: " + (size / 1024) + " KB  ·  last " + lines + " lines"));
        } catch (IOException ignored) {}
        out.println(colorize(color, DIM, "  " + "─".repeat(72)));
        out.println();
    }

    /**
     * Efficiently read the last {@code n} lines from a potentially large file
     * using a reverse-scan with {@link RandomAccessFile}.
     */
    private void printLastLines(PrintStream out, boolean color, Path file, int n)
            throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long fileLen = raf.length();
            if (fileLen == 0) {
                out.println(colorize(color, DIM, "  (empty)"));
                return;
            }

            Deque<String> deque = new ArrayDeque<>(n + 1);
            long pos = fileLen - 1;
            StringBuilder sb = new StringBuilder();

            while (pos >= 0) {
                raf.seek(pos);
                char c = (char) raf.read();

                if (c == '\n' && pos != fileLen - 1) {
                    String line = sb.reverse().toString();
                    sb.setLength(0);
                    deque.addFirst(line);
                    if (deque.size() > n) {
                        break;
                    }
                } else if (c != '\r') {
                    sb.append(c);
                }
                pos--;
            }
            if (sb.length() > 0 && deque.size() < n) {
                deque.addFirst(sb.reverse().toString());
            }

            for (String line : deque) {
                out.println(formatLogLine(color, line));
            }
        }
    }

    /**
     * Tail-follow: poll for new content every 250 ms.
     */
    private void tailFollow(PrintStream out, Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(raf.length()); // jump to end

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                // nothing needed – just let the JVM exit cleanly
            }));

            while (!Thread.currentThread().isInterrupted()) {
                String line = raf.readLine();
                if (line != null) {
                    out.println(formatLogLine(false, line));
                    out.flush();
                } else {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    // ── formatting ──────────────────────────────────────────────────────────

    private String formatLogLine(boolean color, String line) {
        if (!color) return "  " + line;

        // Colour-code based on log level keywords
        if (containsAny(line, "SEVERE", "ERROR", "Exception", "WARN")) {
            return colorize(true, RED, "  " + line);
        }
        if (containsAny(line, "WARNING", "WARN")) {
            return colorize(color, YELLOW, "  " + line);
        }
        if (containsAny(line, "[Aqli-Native]", "INFO", "Rebuild", "Indexed")) {
            return colorize(color, DIM, "  " + line);
        }
        return "  " + line;
    }

    private static boolean containsAny(String line, String... tokens) {
        for (String t : tokens) {
            if (line.contains(t)) return true;
        }
        return false;
    }

    private static String colorize(boolean color, String ansi, String text) {
        return color ? ansi + text + RESET : text;
    }

    private static boolean isColorSupported() {
        if (System.getenv("NO_COLOR") != null) return false;
        if ("dumb".equals(System.getenv("TERM"))) return false;
        return System.console() != null
                || System.getenv("COLORTERM") != null
                || System.getenv("TERM_PROGRAM") != null;
    }
}
