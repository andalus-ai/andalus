package tech.kayys.andalus.cli;

import tech.kayys.andalus.contract.AndalusContractDescriptor;
import tech.kayys.andalus.client.AndalusAqliSdk;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

final class AndalusCliGoldenFixtures {
    static final String UPDATE_PROPERTY = AndalusCliGoldenFixtureIO.UPDATE_PROPERTY;
    static final String UPDATE_INCLUDE_PROPERTY = AndalusCliGoldenFixtureSelection.UPDATE_INCLUDE_PROPERTY;

    private AndalusCliGoldenFixtures() {
    }

    static List<GoldenFixture> all() {
        return Stream.concat(
                        AndalusCliCatalogGoldenFixtures.all().stream(),
                        AndalusCliRunGoldenFixtures.all().stream())
                .toList();
    }

    static List<GoldenFixture> schemaValidated() {
        return all().stream()
                .filter(fixture -> fixture.schemaMode().validated())
                .toList();
    }

    static boolean updateEnabled() {
        return AndalusCliGoldenFixtureIO.updateEnabled();
    }

    static AndalusCliGoldenFixtureSelection selection() {
        return AndalusCliGoldenFixtureSelection.fromSystemProperties();
    }

    static void writeFixture(String name, String payload) throws IOException {
        AndalusCliGoldenFixtureIO.writeFixture(name, payload);
    }

    enum SchemaMode {
        NONE,
        EXPLICIT,
        SELF_DESCRIBING;

        boolean validated() {
            return this != NONE;
        }
    }

    record GoldenFixture(
            String name,
            String sdkSource,
            Supplier<AndalusAqliSdk> sdkFactory,
            List<String> args,
            List<String> commandIds,
            int expectedExitCode,
            SchemaMode schemaMode,
            AndalusContractDescriptor descriptor) {
        private static final String GOLDEN_SUFFIX = ".golden";

        GoldenFixture(
                String name,
                String sdkSource,
                Supplier<AndalusAqliSdk> sdkFactory,
                List<String> args,
                int expectedExitCode,
                SchemaMode schemaMode,
                AndalusContractDescriptor descriptor) {
            this(
                    name,
                    sdkSource,
                    sdkFactory,
                    args,
                    defaultCommandIds(name, schemaMode),
                    expectedExitCode,
                    schemaMode,
                    descriptor);
        }

        GoldenFixture {
            Objects.requireNonNull(name, "name");
            sdkSource = Objects.requireNonNull(sdkSource, "sdkSource").trim();
            if (sdkSource.isEmpty()) {
                throw new IllegalArgumentException("SDK source is required.");
            }
            Objects.requireNonNull(sdkFactory, "sdkFactory");
            args = List.copyOf(args);
            Objects.requireNonNull(schemaMode, "schemaMode");
            commandIds = normalizedCommandIds(commandIds);
            if (schemaMode.validated() && commandIds.isEmpty()) {
                throw new IllegalArgumentException("Schema-validated fixtures must declare at least one command id.");
            }
            if (!schemaMode.validated() && !commandIds.isEmpty()) {
                throw new IllegalArgumentException("Only schema-validated fixtures can declare command ids.");
            }
            if (schemaMode == SchemaMode.EXPLICIT) {
                Objects.requireNonNull(descriptor, "descriptor");
            }
            if (schemaMode != SchemaMode.EXPLICIT && descriptor != null) {
                throw new IllegalArgumentException("Only explicit schema fixtures can carry descriptors.");
            }
        }

        AndalusAqliSdk newSdk() {
            return sdkFactory.get();
        }

        String[] argsArray() {
            return args.toArray(String[]::new);
        }

        String commandLine() {
            return AndalusCliCommandLines.render(args);
        }

        private static List<String> defaultCommandIds(String name, SchemaMode schemaMode) {
            if (schemaMode == null || !schemaMode.validated()) {
                return List.of();
            }
            return List.of(defaultCommandId(name));
        }

        private static String defaultCommandId(String fixtureName) {
            String normalized = Objects.requireNonNull(fixtureName, "fixtureName").trim();
            return normalized.endsWith(GOLDEN_SUFFIX)
                    ? normalized.substring(0, normalized.length() - GOLDEN_SUFFIX.length())
                    : normalized;
        }

        private static List<String> normalizedCommandIds(List<String> commandIds) {
            if (commandIds == null || commandIds.isEmpty()) {
                return List.of();
            }
            List<String> normalized = commandIds.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
            if (normalized.size() != commandIds.size()) {
                throw new IllegalArgumentException("Command ids must be non-blank and unique.");
            }
            return normalized;
        }
    }
}
