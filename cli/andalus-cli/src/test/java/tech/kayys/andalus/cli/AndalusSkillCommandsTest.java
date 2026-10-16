package tech.kayys.andalus.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import tech.kayys.andalus.cli.AndalusAqliCli;
import tech.kayys.andalus.cli.AndalusSkillCommands;
import tech.kayys.andalus.client.AndalusAqliSdk;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AndalusSkillCommandsTest {

    @Test
    void skillCommandsAreRegisteredFromDedicatedModule() {
        CommandLine root = commandLine();
        Map<String, CommandLine> commands = root.getSubcommands();
        CommandLine skills = commands.get("skills");
        Map<String, CommandLine> subcommands = skills.getSubcommands();

        assertThat((Object) skills.getCommand())
                .isInstanceOf(AndalusSkillCommands.SkillsCommand.class);
        assertThat((Object) commands.get("capabilities").getCommand())
                .isInstanceOf(AndalusSkillCommands.SkillsCommand.class);
        assertThat((Object) subcommands.get("list").getCommand())
                .isInstanceOf(AndalusSkillCommands.SkillsCommand.ListCommand.class);
        assertThat((Object) subcommands.get("inspect").getCommand())
                .isInstanceOf(AndalusSkillCommands.SkillsCommand.InspectCommand.class);
        assertThat((Object) subcommands.get("search").getCommand())
                .isInstanceOf(AndalusSkillCommands.SkillsCommand.SearchCommand.class);
    }

    @Test
    void extractedSkillListCommandUsesContextOutputAndSdk() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "skills",
                "list",
                "--surface",
                "assistant-agent",
                "--source",
                "rag",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"product\":\"Andalus\"")
                .contains("\"skillIds\":[\"rag.retrieve\"]");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void extractedSkillInspectCommandUsesContextOutputAndSdk() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "skills",
                "inspect",
                "rag",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"skillId\":\"rag.retrieve\"")
                .contains("\"source\":\"rag\"");
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
