package tech.kayys.andalus.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import tech.kayys.andalus.cli.AndalusAqliCli;
import tech.kayys.andalus.client.AndalusAqliSdk;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AndalusRunEventCommandsTest {

    @Test
    void runEventCommandsAreRegisteredFromDedicatedModule() {
        CommandLine root = commandLine();
        CommandLine run = root.getSubcommands().get("run");
        Map<String, CommandLine> subcommands = run.getSubcommands();

        assertThat((Object) subcommands.get("events").getCommand())
                .isInstanceOf(AndalusRunEventCommands.EventsCommand.class);
    }

    @Test
    void eventCommandsAcceptStandardHelpOption() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "run",
                "events",
                "--help");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("Usage: andalus run events")
                .contains("Show a lifecycle event timeline");
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
