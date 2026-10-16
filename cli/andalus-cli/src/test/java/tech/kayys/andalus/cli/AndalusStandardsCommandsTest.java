package tech.kayys.andalus.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import tech.kayys.andalus.cli.AndalusAqliCli;
import tech.kayys.andalus.cli.AndalusStandardsCommands;
import tech.kayys.andalus.client.AndalusAqliSdk;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AndalusStandardsCommandsTest {

    @Test
    void standardsCommandsAreRegisteredFromDedicatedModule() {
        CommandLine root = commandLine();
        Map<String, CommandLine> commands = root.getSubcommands();

        assertThat((Object) commands.get("standards").getCommand())
                .isInstanceOf(AndalusStandardsCommands.StandardsCommand.class);
        assertThat((Object) commands.get("standard-alignment").getCommand())
                .isInstanceOf(AndalusStandardsCommands.StandardsCommand.class);
        assertThat((Object) commands.get("alignment").getCommand())
                .isInstanceOf(AndalusStandardsCommands.StandardsCommand.class);
    }

    @Test
    void extractedStandardsCommandUsesContextOutputAndSdk() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "standard-alignment",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"product\":\"Andalus\"")
                .contains("\"status\":\"ready\"")
                .contains("\"ready\":true");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void extractedStandardsCommandPreservesReadinessExitCode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "alignment",
                "--policy",
                "pinned-known",
                "--json");

        assertThat(exitCode).isEqualTo(1);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"status\":\"blocked\"")
                .contains("\"ready\":false")
                .contains("\"missingStandardIds\":[\"a2a\",\"a2ui\",\"agentic-commerce\"]");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void standardsCommandCanRenderCatalogAsJson() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "standards",
                "--catalog",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"product\":\"Andalus\"")
                .contains("\"totalStandards\":3")
                .contains("\"standardIds\":[\"a2a\",\"a2ui\",\"agentic-commerce\"]")
                .contains("\"bindingCounts\":{\"JSONRPC\":1,\"HTTP\":1,\"HTTP+JSON\":1}")
                .contains("\"version\":\"v0.8\"")
                .contains("\"attributes\":{\"extensionUri\":\"https://a2ui.org/a2a-extension/a2ui/v0.8\"}");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void standardsCommandCanRenderCatalogAsText() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "standards",
                "--registry");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("Andalus standards catalog")
                .contains("standards: 3")
                .contains("standardIds: [a2a, a2ui, agentic-commerce]")
                .contains("- a2ui Agent-to-User Interface v0.8 [HTTP]")
                .contains("specUrl: https://a2ui.org/specification/v0_8/standard_catalog_definition.json");
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
