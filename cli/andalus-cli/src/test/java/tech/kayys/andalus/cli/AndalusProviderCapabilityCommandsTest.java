package tech.kayys.andalus.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import tech.kayys.andalus.cli.AndalusAqliCli;
import tech.kayys.andalus.cli.AndalusProviderCapabilityCommands;
import tech.kayys.andalus.client.AndalusAqliSdk;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AndalusProviderCapabilityCommandsTest {

    @Test
    void providerCapabilityCommandsAreRegisteredFromDedicatedModule() {
        CommandLine root = commandLine();
        Map<String, CommandLine> commands = root.getSubcommands();
        CommandLine providers = commands.get("providers");
        Map<String, CommandLine> subcommands = providers.getSubcommands();

        assertThat((Object) providers.getCommand())
                .isInstanceOf(AndalusProviderCapabilityCommands.ProvidersCommand.class);
        assertThat((Object) commands.get("provider-capabilities").getCommand())
                .isInstanceOf(AndalusProviderCapabilityCommands.ProvidersCommand.class);
        assertThat((Object) subcommands.get("list").getCommand())
                .isInstanceOf(AndalusProviderCapabilityCommands.ProvidersCommand.ListCommand.class);
        assertThat((Object) subcommands.get("inspect").getCommand())
                .isInstanceOf(AndalusProviderCapabilityCommands.ProvidersCommand.InspectCommand.class);
        assertThat((Object) subcommands.get("search").getCommand())
                .isInstanceOf(AndalusProviderCapabilityCommands.ProvidersCommand.SearchCommand.class);
    }

    @Test
    void providerCapabilityListCommandFiltersAndRendersJson() {
        Console console = execute(
                "providers",
                "list",
                "--module",
                "a2ui",
                "--json");

        assertThat(console.exitCode()).isZero();
        assertThat(console.out())
                .contains("\"product\":\"Andalus\"")
                .contains("\"moduleId\":\"a2ui\"")
                .contains("\"matchingCapabilities\":1")
                .contains("\"capabilityIds\":[\"a2ui.contracts\"]")
                .contains("\"standardIds\":[\"a2ui\"]")
                .contains("\"providerId\":\"andalus-a2ui\"")
                .contains("\"tags\":[\"a2ui\",\"standard\",\"ui-contracts\",\"pro\",\"enterprise\",\"addon\"]")
                .contains("\"activationProfile\":\"pro-enterprise-addons\"")
                .contains("\"defaultCommunity\":false");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void providerCapabilitySearchCommandFiltersThroughSdkDiscovery() {
        Console console = execute(
                "providers",
                "search",
                "lifecycle",
                "--surface",
                "coding-agent",
                "--json");

        assertThat(console.exitCode()).isZero();
        assertThat(console.out())
                .contains("\"search\":\"lifecycle\"")
                .contains("\"surfaceId\":\"coding-agent\"")
                .contains("\"capabilityIds\":[\"runtime.lifecycle\"]")
                .doesNotContain("\"id\":\"rag.retrieve\"");
        assertThat(console.err()).isEmpty();
    }

    @Test
    void providerCapabilityInspectCommandRendersTextDetail() {
        Console console = execute(
                "providers",
                "inspect",
                "storage.hybrid-persistence");

        assertThat(console.exitCode()).isZero();
        assertThat(console.out())
                .contains("Andalus provider capability")
                .contains("id: storage.hybrid-persistence")
                .contains("name: Hybrid Persistence")
                .contains("provider: andalus-storage")
                .contains("module: storage")
                .contains("tags: database, object-storage, file-fallback, rustfs, s3")
                .contains("metadata: fallback=files");
        assertThat(console.err()).isEmpty();
    }

    private static CommandLine commandLine() {
        return new CommandLine(new AndalusAqliCli(
                AndalusAqliSdk.local(),
                stream(new ByteArrayOutputStream()),
                stream(new ByteArrayOutputStream())));
    }

    private static Console execute(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                args);
        return new Console(
                exitCode,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private static PrintStream stream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }

    private record Console(int exitCode, String out, String err) {
    }
}
