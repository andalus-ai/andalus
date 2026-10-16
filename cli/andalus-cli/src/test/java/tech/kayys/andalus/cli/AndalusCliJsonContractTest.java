package tech.kayys.andalus.cli;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import tech.kayys.andalus.contract.AndalusContractCatalog;
import tech.kayys.andalus.contract.AndalusContractDescriptor;
import tech.kayys.andalus.contract.AndalusContractDiscovery;
import tech.kayys.andalus.contract.AndalusContractJsonSchema;
import tech.kayys.andalus.contract.AndalusContractQuery;
import tech.kayys.andalus.client.AndalusAqliSdk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

class AndalusCliJsonContractTest {

    @TestFactory
    Stream<DynamicTest> goldenJsonPayloadsMatchGoldenContracts() {
        AndalusCliGoldenFixtureSelection selection = AndalusCliGoldenFixtures.selection();
        return AndalusCliGoldenFixtures.all().stream()
                .map(fixture -> dynamicTest(fixture.name(), () -> assertGolden(fixture, selection)));
    }

    @Test
    void goldenJsonPayloadsMatchPublishedSchemas() throws IOException {
        if (AndalusCliGoldenFixtures.updateEnabled()) {
            return;
        }
        AndalusAqliSdk sdk = AndalusAqliSdk.local();

        for (AndalusCliGoldenFixtures.GoldenFixture fixture : AndalusCliGoldenFixtures.schemaValidated()) {
            if (fixture.schemaMode() == AndalusCliGoldenFixtures.SchemaMode.EXPLICIT) {
                assertFixtureMatchesSchema(sdk, fixture.name(), fixture.descriptor());
            } else {
                assertSelfDescribingFixtureMatchesSchema(sdk, fixture.name());
            }
        }
    }

    @Test
    void goldenFixtureCatalogCoversResources() throws IOException, URISyntaxException {
        List<String> catalogNames = AndalusCliGoldenFixtures.all().stream()
                .map(AndalusCliGoldenFixtures.GoldenFixture::name)
                .toList();

        assertThat(catalogNames).doesNotHaveDuplicates();
        AndalusCliGoldenFixtureSelection selection = AndalusCliGoldenFixtures.selection();
        assertThat(selection.unknownIncludes(Set.copyOf(catalogNames)))
                .as(AndalusCliGoldenFixtures.UPDATE_INCLUDE_PROPERTY + " entries must match known fixture names")
                .isEmpty();
        if (AndalusCliGoldenFixtures.updateEnabled()) {
            return;
        }

        for (String name : catalogNames) {
            assertThat(fixture(name)).as(name + " fixture resource").isNotBlank();
        }

        Path contracts = Path.of(Objects.requireNonNull(
                        AndalusCliJsonContractTest.class.getResource("/contracts"),
                        "contracts resource directory")
                .toURI());
        try (var resources = Files.list(contracts)) {
            List<String> resourceNames = resources
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".golden"))
                    .toList();
            assertThat(resourceNames).containsExactlyInAnyOrderElementsOf(catalogNames);
        }
    }

    @Test
    void filteredWorkbenchJsonKeepsDiscoveryContractShape() {
        CliResult result = execute(
                "workbench",
                "--surface",
                "assistant-agent",
                "--category",
                "Runs",
                "--id",
                "run-session-context",
                "--json");

        assertThat(result.exitCode()).isZero();
        assertThat(result.err()).isEmpty();
        assertThat(trimmed(result.out()))
                .startsWith("{\"product\":\"Andalus\",\"status\":")
                .contains("\"commandQuery\":{\"surfaceId\":\"assistant-agent\",\"profileId\":null,\"resolvedSurfaceId\":\"assistant-agent\",\"category\":\"Runs\",\"commandId\":\"run-session-context\",\"contractJsonSchemaId\":null,\"filtered\":true}")
                .contains("\"commandPalette\":[\"run <task> --session <id> --user <id> --context rag.collection=<name>\"]")
                .contains("\"commands\":[{\"id\":\"run-session-context\",\"title\":\"Run With Session Context\"")
                .contains("\"surfaceIds\":[\"assistant-agent\"],\"localOnly\":false")
                .contains("\"nextActions\":[")
                .doesNotContain("\"id\":\"run-assistant-surface\"")
                .doesNotContain("\"id\":\"run-print-spec-output\"");
    }

    private static void assertGolden(
            AndalusCliGoldenFixtures.GoldenFixture fixture,
            AndalusCliGoldenFixtureSelection selection) throws IOException {
        CliResult result = execute(fixture.newSdk(), fixture.argsArray());

        assertThat(result.exitCode()).isEqualTo(fixture.expectedExitCode());
        assertThat(result.err()).isEmpty();
        String output = trimmed(result.out());
        if (AndalusCliGoldenFixtures.updateEnabled() && selection.selected(fixture.name())) {
            AndalusCliGoldenFixtures.writeFixture(fixture.name(), output);
            return;
        }
        assertThat(output).isEqualTo(fixture(fixture.name()));
    }

    private static CliResult execute(String... args) {
        return execute(AndalusAqliSdk.local(), args);
    }

    private static CliResult execute(AndalusAqliSdk sdk, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = AndalusAqliCli.execute(
                sdk,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                args);
        return new CliResult(
                exitCode,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private static String fixture(String name) throws IOException {
        String path = "/contracts/" + name;
        try (InputStream input = AndalusCliJsonContractTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing JSON contract fixture: " + path);
            }
            return trimmed(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static void assertFixtureMatchesSchema(
            AndalusAqliSdk sdk,
            String fixture,
            AndalusContractDescriptor descriptor) throws IOException {
        AndalusContractJsonSchema schema = sdk.contractJsonSchema(descriptor);
        List<String> lines = fixture(fixture).lines()
                .filter(line -> !line.isBlank())
                .toList();
        for (int i = 0; i < lines.size(); i++) {
            assertJsonMatchesSchema(schema, TestJson.parse(lines.get(i)), fixture + ":" + (i + 1));
        }
    }

    private static void assertSelfDescribingFixtureMatchesSchema(AndalusAqliSdk sdk, String fixture)
            throws IOException {
        List<String> lines = fixture(fixture).lines()
                .filter(line -> !line.isBlank())
                .toList();
        for (int i = 0; i < lines.size(); i++) {
            Object payload = TestJson.parse(lines.get(i));
            AndalusContractJsonSchema schema = sdk.contractJsonSchema(descriptorFromPayload(payload, fixture));
            assertJsonMatchesSchema(schema, payload, fixture + ":" + (i + 1));
        }
    }

    private static void assertJsonMatchesSchema(AndalusContractJsonSchema schema, Object payload, String label) {
        List<String> errors = TestJsonSchemaValidator.validate(schema.document(), payload);
        assertThat(errors)
                .as(label + " should match " + schema.id())
                .isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static AndalusContractDescriptor descriptorFromPayload(Object payload, String fixture) {
        assertThat(payload)
                .as(fixture + " root JSON value")
                .isInstanceOf(Map.class);
        Map<String, Object> object = (Map<String, Object>) payload;
        assertThat(object.get("contract"))
                .as(fixture + " contract")
                .isInstanceOf(Map.class);
        Map<String, Object> contract = (Map<String, Object>) object.get("contract");
        return descriptor(
                String.valueOf(contract.get("schema")),
                String.valueOf(contract.get("envelope")));
    }

    private static AndalusContractDescriptor descriptor(String schema, String envelope) {
        AndalusContractDiscovery discovery = AndalusContractCatalog.discover(AndalusContractQuery.of(schema, envelope));
        assertThat(discovery.contracts())
                .as(schema + "/" + envelope + " schema descriptor")
                .hasSize(1);
        return discovery.contracts().get(0);
    }

    private record CliResult(int exitCode, String out, String err) {
    }
}
