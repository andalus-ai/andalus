package tech.kayys.andalus.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import tech.kayys.andalus.cli.AndalusAqliCli;
import tech.kayys.andalus.cli.contract.AndalusContractCommands;
import tech.kayys.andalus.client.AndalusAqliSdk;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AndalusContractCommandsTest {

    @Test
    void contractCommandsAreRegisteredFromDedicatedModule() {
        CommandLine root = commandLine();
        Map<String, CommandLine> commands = root.getSubcommands();

        assertThat((Object) commands.get("contracts").getCommand())
                .isInstanceOf(AndalusContractCommands.ContractsCommand.class);
        assertThat((Object) commands.get("schemas").getCommand())
                .isInstanceOf(AndalusContractCommands.ContractsCommand.class);
    }

    @Test
    void extractedContractCommandUsesContextOutputAndSdk() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "contracts",
                "--domain",
                "planning",
                "--index",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"product\":\"Andalus\"")
                .contains("\"domain\":\"planning\"")
                .contains("\"matchingContracts\":2")
                .contains("\"schemas\":[\"andalus.run.planning\"]");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void extractedContractCoverageUsesContextOutputAndSdk() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "contracts",
                "--coverage",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"commandlessContracts\":1")
                .contains("\"schema\":\"andalus.readiness\"")
                .contains("\"envelope\":\"readiness-report\"")
                .doesNotContain("\"envelope\":\"readiness-aggregate\"");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void extractedContractSchemaExportUsesContextOutputAndSdk() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "schemas",
                "--envelope",
                "run-preview",
                "--schema-json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"$id\":\"urn:andalus:contract:andalus.run.planning:v1:run-preview\"")
                .contains("\"required\":[\"contract\",\"requestId\",\"tenantId\"");
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
