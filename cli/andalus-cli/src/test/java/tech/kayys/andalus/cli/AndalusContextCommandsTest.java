package tech.kayys.andalus.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import tech.kayys.andalus.cli.AndalusContextCommands;
import tech.kayys.andalus.cli.AndalusAqliCli;
import tech.kayys.andalus.client.AndalusAqliSdk;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AndalusContextCommandsTest {

    @Test
    void contextCommandsAreRegisteredFromDedicatedModule() {
        CommandLine root = commandLine();
        Map<String, CommandLine> commands = root.getSubcommands();

        assertThat((Object) commands.get("workspace").getCommand())
                .isInstanceOf(AndalusContextCommands.WorkspaceCommand.class);
        assertThat((Object) commands.get("inspect").getCommand())
                .isInstanceOf(AndalusContextCommands.WorkspaceCommand.class);
        assertThat((Object) commands.get("harness").getCommand())
                .isInstanceOf(AndalusContextCommands.HarnessCommand.class);
        assertThat((Object) commands.get("checks").getCommand())
                .isInstanceOf(AndalusContextCommands.HarnessCommand.class);
    }

    @Test
    void extractedWorkspaceCommandUsesContextOutputAndSdk() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "workspace",
                "--path",
                ".",
                "--max-entries",
                "1",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"rootPath\":")
                .contains("\"exists\":true")
                .contains("\"importantPaths\":");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void extractedHarnessCommandUsesContextOutputAndSdk() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "harness",
                "--path",
                ".",
                "--max-checks",
                "2",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"workspace\":")
                .contains("\"checks\":");
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
