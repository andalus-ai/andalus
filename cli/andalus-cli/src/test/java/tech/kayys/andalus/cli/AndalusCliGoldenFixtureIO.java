package tech.kayys.andalus.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

final class AndalusCliGoldenFixtureIO {
    static final String UPDATE_PROPERTY = "andalus.golden.update";

    private AndalusCliGoldenFixtureIO() {
    }

    static boolean updateEnabled() {
        return Boolean.getBoolean(UPDATE_PROPERTY);
    }

    static String readFixture(String name) throws IOException {
        String resourcePath = "/contracts/" + name;
        try (InputStream input = AndalusCliGoldenFixtureIO.class.getResourceAsStream(resourcePath)) {
            if (input != null) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        }
        Path path = contractsDirectory().resolve(name);
        if (Files.exists(path)) {
            return Files.readString(path, StandardCharsets.UTF_8).trim();
        }
        throw new IllegalArgumentException("Missing JSON contract fixture: " + resourcePath);
    }

    static void writeFixture(String name, String payload) throws IOException {
        writeFixture(contractsDirectory(), name, payload);
    }

    static boolean writeFixture(Path contractsDirectory, String name, String payload) throws IOException {
        String normalized = normalizedPayload(payload);
        Path path = contractsDirectory.resolve(name);
        Files.createDirectories(path.getParent());
        if (Files.exists(path) && Files.readString(path, StandardCharsets.UTF_8).equals(normalized)) {
            return false;
        }
        Files.writeString(path, normalized, StandardCharsets.UTF_8);
        return true;
    }

    private static String normalizedPayload(String payload) {
        String normalized = payload == null ? "" : payload.trim();
        return normalized + System.lineSeparator();
    }

    private static Path contractsDirectory() {
        List<Path> candidates = List.of(
                Paths.get("src", "test", "resources", "contracts"),
                Paths.get("andalus-aqli-cli", "src", "test", "resources", "contracts"),
                Paths.get("andalus-aqli", "andalus-aqli-cli", "src", "test", "resources", "contracts"));
        return candidates.stream()
                .filter(Files::exists)
                .findFirst()
                .orElse(candidates.get(0));
    }
}
