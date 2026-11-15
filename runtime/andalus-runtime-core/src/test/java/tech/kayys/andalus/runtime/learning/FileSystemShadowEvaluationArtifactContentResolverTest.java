package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemShadowEvaluationArtifactContentResolverTest {
    @TempDir Path temporaryDirectory;

    @Test
    void resolvesExactArtifactBytesWithoutAnArtifactLoader() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("artifacts"));
        byte[] expected = "signed evaluator bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(root.resolve("candidate.jar"), expected);

        var resolver = new FileSystemShadowEvaluationArtifactContentResolver(root);
        try (var resolved = resolver.resolve("candidate.jar")) {
            byte[] actual = resolved.content().readAllBytes();
            assertThat(actual).containsExactly(expected);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual)))
                    .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)));
        }
    }

    @Test
    void rejectsTraversalAndAbsolutePaths() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("artifacts"));
        var resolver = new FileSystemShadowEvaluationArtifactContentResolver(root);

        assertThatThrownBy(() -> resolver.resolve("../outside.jar"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(root.resolve("outside.jar").toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSymlinks() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("artifacts"));
        Path outside = Files.writeString(temporaryDirectory.resolve("outside.jar"), "artifact");
        Path link = root.resolve("linked.jar");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            return;
        }
        var resolver = new FileSystemShadowEvaluationArtifactContentResolver(root);
        assertThatThrownBy(() -> resolver.resolve("linked.jar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbolic links");
    }
}
