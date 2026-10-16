package tech.kayys.andalus.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import tech.kayys.andalus.cli.AndalusAqliCli;
import tech.kayys.andalus.cli.AndalusPlatformCommands;
import tech.kayys.andalus.client.LocalAndalusAqliSdk;
import tech.kayys.andalus.client.AndalusAqliSdk;
import tech.kayys.andalus.client.AndalusAqliSdkConfig;
import tech.kayys.andalus.readiness.AndalusPlatformReadinessProfileRegistryConfig;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AndalusPlatformCommandsTest {

    @Test
    void platformCommandsAreRegisteredFromDedicatedModule() {
        CommandLine root = commandLine();
        Map<String, CommandLine> commands = root.getSubcommands();

        assertThat((Object) commands.get("status").getCommand())
                .isInstanceOf(AndalusPlatformCommands.StatusCommand.class);
        assertThat((Object) commands.get("products").getCommand())
                .isInstanceOf(AndalusPlatformCommands.ProductsCommand.class);
        assertThat((Object) commands.get("sdk-boundaries").getCommand())
                .isInstanceOf(AndalusPlatformCommands.SdkBoundariesCommand.class);
        assertThat((Object) commands.get("readiness-profiles").getCommand())
                .isInstanceOf(AndalusPlatformCommands.ReadinessProfilesCommand.class);
        assertThat((Object) commands.get("readiness-profiles").getSubcommands().get("config").getCommand())
                .isInstanceOf(AndalusPlatformCommands.ReadinessProfilesCommand.ConfigCommand.class);
        assertThat((Object) commands.get("readiness-profiles").getSubcommands().get("inspect").getCommand())
                .isInstanceOf(AndalusPlatformCommands.ReadinessProfilesCommand.InspectCommand.class);
        assertThat((Object) commands.get("readiness-profiles").getSubcommands().get("policies").getCommand())
                .isInstanceOf(AndalusPlatformCommands.ReadinessProfilesCommand.PoliciesCommand.class);
        assertThat((Object) commands.get("readiness-profiles").getSubcommands().get("preflight").getCommand())
                .isInstanceOf(AndalusPlatformCommands.ReadinessProfilesCommand.PreflightCommand.class);
        assertThat((Object) commands.get("readiness-profiles").getSubcommands().get("providers").getCommand())
                .isInstanceOf(AndalusPlatformCommands.ReadinessProfilesCommand.ProvidersCommand.class);
        assertThat((Object) commands.get("readiness-profiles").getSubcommands().get("sources").getCommand())
                .isInstanceOf(AndalusPlatformCommands.ReadinessProfilesCommand.SourcesCommand.class);
        assertThat((Object) commands.get("profiles").getCommand())
                .isInstanceOf(AndalusPlatformCommands.ProfilesCommand.class);
        assertThat((Object) commands.get("profiles").getSubcommands().get("inspect").getCommand())
                .isInstanceOf(AndalusPlatformCommands.ProfilesCommand.InspectCommand.class);
    }

    @Test
    void sdkBoundariesCommandRendersBoundaryCatalogJson() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "sdk-boundaries",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"product\":\"Andalus\"")
                .contains("\"rootPackage\":\"tech.kayys.andalus.aqli.sdk\"")
                .contains("\"boundaryIds\":[\"core\",\"run\",\"context\",\"capability\"")
                .contains("\"id\":\"remote\"");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void sdkBoundariesCommandRendersSingleBoundaryText() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "sdk-boundaries",
                "run");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("Andalus SDK boundary")
                .contains("Run Lifecycle (run)")
                .contains("package: tech.kayys.andalus.aqli.sdk.run")
                .contains("contract schemas: andalus.run.planning, andalus.run.lifecycle");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void extractedProfileInspectCommandKeepsRootParentWiring() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "profiles",
                "inspect",
                "openclaw-agent",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"id\":\"openclaw-agent\"")
                .contains("\"surfaceId\":\"coding-agent\"");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void readinessProfileProvidersCommandRendersPlatformDiscoveryJson() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "readiness-profiles",
                "providers",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"product\":\"Andalus\"")
                .contains("\"ready\":true")
                .contains("\"requiredReaderTypes\":[]");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void readinessProfilePreflightCommandRendersRegistryPreflightJson() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "readiness-profiles",
                "preflight",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"product\":\"Andalus\"")
                .contains("\"ready\":true")
                .contains("\"providerDiscoveryRequired\":false")
                .contains("\"registryReady\":true");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void readinessProfileConfigCommandRedactsDatabaseUrlSecretsInTextOutput() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        AndalusAqliSdk sdk = new LocalAndalusAqliSdk(AndalusAqliSdkConfig.local()
                .withReadinessProfileRegistry(AndalusPlatformReadinessProfileRegistryConfig.fromMap(Map.of(
                        "mode", "database",
                        "databaseUrl",
                        "jdbc:postgresql://ops:super-secret@localhost:5432/andalus?password=top-secret&token=api-secret",
                        "fallbackToBuiltIn", true))));

        int exitCode = AndalusAqliCli.execute(
                sdk,
                stream(out),
                stream(err),
                "readiness-profiles",
                "config");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("password=<redacted>")
                .contains("token=<redacted>")
                .contains("ops:<redacted>@localhost")
                .doesNotContain("top-secret")
                .doesNotContain("api-secret")
                .doesNotContain("super-secret");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void readinessProfileConfigCommandRedactsObjectStorageCredentialsInJsonOutput() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        AndalusAqliSdk sdk = new LocalAndalusAqliSdk(AndalusAqliSdkConfig.local()
                .withReadinessProfileRegistry(AndalusPlatformReadinessProfileRegistryConfig.fromMap(Map.of(
                        "mode", "s3",
                        "endpoint", "https://s3.example.test",
                        "bucket", "andalus",
                        "keyPrefix", "profiles/default.properties",
                        "credentials", "accessKeyId=inline-access secretAccessKey=inline-secret"))));

        int exitCode = AndalusAqliCli.execute(
                sdk,
                stream(out),
                stream(err),
                "readiness-profiles",
                "config",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("accessKeyId=<redacted>")
                .contains("secretAccessKey=<redacted>")
                .doesNotContain("inline-access")
                .doesNotContain("inline-secret");
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
