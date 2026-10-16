package tech.kayys.andalus.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import tech.kayys.andalus.cli.AndalusAqliCli;
import tech.kayys.andalus.cli.AndalusWorkbenchCommands;
import tech.kayys.andalus.client.AndalusAqliSdk;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AndalusWorkbenchCommandsTest {

    @Test
    void workbenchCommandsAreRegisteredFromDedicatedModule() {
        CommandLine root = commandLine();
        Map<String, CommandLine> commands = root.getSubcommands();

        assertThat((Object) commands.get("commands").getCommand())
                .isInstanceOf(AndalusWorkbenchCommands.CommandsCommand.class);
        assertThat((Object) commands.get("actions").getCommand())
                .isInstanceOf(AndalusWorkbenchCommands.CommandsCommand.class);
        assertThat((Object) commands.get("workbench").getCommand())
                .isInstanceOf(AndalusWorkbenchCommands.WorkbenchCommand.class);
        assertThat((Object) commands.get("dashboard").getCommand())
                .isInstanceOf(AndalusWorkbenchCommands.WorkbenchCommand.class);
    }

    @Test
    void extractedCommandsCommandUsesContextOutputAndSdk() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "commands",
                "--surface",
                "assistant-agent",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"query\":{\"surfaceId\":\"assistant-agent\"")
                .contains("\"commandIds\":[")
                .contains("\"id\":\"run-session-context\"");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void extractedDashboardAliasUsesContextOutputAndSdk() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "dashboard",
                "--surface",
                "assistant-agent",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"product\":\"Andalus\"")
                .contains("\"commandQuery\":{\"surfaceId\":\"assistant-agent\"")
                .contains("\"commandPalette\":")
                .contains("\"nextActions\":");
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
