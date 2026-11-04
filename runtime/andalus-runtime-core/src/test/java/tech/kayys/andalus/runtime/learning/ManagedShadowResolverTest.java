package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedShadowResolverTest {
    @TempDir Path temporaryDirectory;

    @Test
    void keyStoreResolverDoesNotResolveUnknownAliasesOrOtherAlgorithms() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        var resolver = new KeyStoreShadowEvaluationPublicKeyResolver(keyStore);

        assertThat(resolver.resolve("signer", "unmanaged-alias", "Ed25519")).isEmpty();
        assertThat(resolver.resolve("signer", "unmanaged-alias", "RSA")).isEmpty();
        assertThat(resolver.resolve(" ", "alias", "Ed25519")).isEmpty();
    }

    @Test
    void filesystemResolverRejectsTraversalAndAbsolutePathsBeforeLoading() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("artifacts"));
        var resolver = new FileSystemShadowEvaluationArtifactResolver(root, (reference, bytes) -> null);

        assertThatThrownBy(() -> resolver.resolve("../outside.jar"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(root.resolve("outside.jar").toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filesystemResolverRejectsSymlinks() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("artifacts"));
        Path outside = Files.writeString(temporaryDirectory.resolve("outside.jar"), "artifact");
        Path link = root.resolve("linked.jar");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            return; // Platform does not permit symlink creation in this test environment.
        }
        var resolver = new FileSystemShadowEvaluationArtifactResolver(root, (reference, bytes) -> null);
        assertThatThrownBy(() -> resolver.resolve("linked.jar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbolic links");
    }
}
