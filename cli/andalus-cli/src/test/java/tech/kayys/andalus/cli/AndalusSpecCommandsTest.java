package tech.kayys.andalus.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import tech.kayys.andalus.cli.AndalusAqliCli;
import tech.kayys.andalus.cli.AndalusSpecCommands;
import tech.kayys.andalus.client.AndalusAqliSdk;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AndalusSpecCommandsTest {

    @Test
    void specCommandsAreRegisteredFromDedicatedModule() {
        CommandLine root = commandLine();
        CommandLine spec = root.getSubcommands().get("spec");
        Map<String, CommandLine> subcommands = spec.getSubcommands();

        assertThat((Object) spec.getCommand())
                .isInstanceOf(AndalusSpecCommands.SpecCommand.class);
        assertThat((Object) subcommands.get("validate").getCommand())
                .isInstanceOf(AndalusSpecCommands.SpecCommand.ValidateCommand.class);
        assertThat((Object) subcommands.get("template").getCommand())
                .isInstanceOf(AndalusSpecCommands.SpecCommand.TemplateCommand.class);
    }

    @Test
    void extractedTemplateCommandUsesContextOutputAndRunSpecs() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "spec",
                "template",
                "--profile",
                "assistant-agent");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("profileId=assistant-agent" + System.lineSeparator())
                .contains("surfaceId=assistant-agent" + System.lineSeparator());
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
