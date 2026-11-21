package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class FileShadowEvaluationWorkerTrustStoreTest {
    @TempDir Path temp;

    @Test
    void loadsOnlyPinnedEd25519KeysFromReadOnlySnapshot() throws Exception {
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.writeString(temp.resolve("trusted-signers.ed25519"),
                "release-2026=" + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()) + "\n");

        var store = new FileShadowEvaluationWorkerTrustStore(temp);

        assertTrue(store.findActiveSigner("release-2026").isPresent());
        assertTrue(store.findActiveSigner("unknown").isEmpty());
    }

    @Test
    void rejectsDuplicateSignerIds() throws Exception {
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String key = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        Files.writeString(temp.resolve("trusted-signers.ed25519"),
                "release=" + key + "\nrelease=" + key + "\n");

        assertThrows(Exception.class, () -> new FileShadowEvaluationWorkerTrustStore(temp));
    }

    @Test
    void rejectsMissingTrustBundle() {
        assertThrows(Exception.class, () -> new FileShadowEvaluationWorkerTrustStore(temp));
    }
}
