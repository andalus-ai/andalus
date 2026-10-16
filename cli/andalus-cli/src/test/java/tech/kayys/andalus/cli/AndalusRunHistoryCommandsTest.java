package tech.kayys.andalus.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import tech.kayys.andalus.cli.run.AndalusRunHistoryCommands;
import tech.kayys.andalus.client.AndalusAqliSdk;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AndalusRunHistoryCommandsTest {

    @Test
    void runHistoryCommandsAreRegisteredFromDedicatedModule() {
        CommandLine root = commandLine();
        CommandLine run = root.getSubcommands().get("run");
        Map<String, CommandLine> subcommands = run.getSubcommands();

        assertThat((Object) subcommands.get("list").getCommand())
                .isInstanceOf(AndalusRunHistoryCommands.ListCommand.class);
        assertThat((Object) subcommands.get("stats").getCommand())
                .isInstanceOf(AndalusRunHistoryCommands.StatsCommand.class);
    }

    @Test
    void historyCommandsAcceptStandardHelpOption() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "run",
                "list",
                "--help");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("Usage: andalus run list")
                .contains("List lifecycle status snapshots");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    private static CommandLine commandLine() {
        return new CommandLine(new AndalusAqliCli(
                AndalusAqliSdk.local(),
                stream(new ByteArrayOutputStream()),
                stream(new ByteArrayOutputStream())));
    }

    private static PrintStream stream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }
}
