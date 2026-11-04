package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ShadowEvaluationSigningTest {
    @Test
    void canonicalDatasetPayloadIsDeterministicAndDomainSeparated() throws Exception {
        var manifest = new ShadowEvaluationManifest.DatasetManifest(
                "dataset", "v1", "schema-v1", 2, "a".repeat(64),
                "artifact-store:datasets/dataset/v1", "signer", "placeholder");
        byte[] first = ShadowEvaluationSigning.datasetPayload(manifest);
        byte[] second = ShadowEvaluationSigning.datasetPayload(manifest);
        assertThat(first).containsExactly(second);

        var changed = new ShadowEvaluationManifest.DatasetManifest(
                "dataset", "v2", "schema-v1", 2, "a".repeat(64),
                "artifact-store:datasets/dataset/v1", "signer", "placeholder");
        assertThat(ShadowEvaluationSigning.datasetPayload(changed)).isNotEqualTo(first);
    }

    @Test
    void ed25519SignatureCoversCanonicalManifestFields() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var manifest = new ShadowEvaluationManifest.DatasetManifest(
                "dataset", "v1", "schema-v1", 2, "a".repeat(64),
                "artifact-store:datasets/dataset/v1", "signer", "placeholder");
        byte[] payload = ShadowEvaluationSigning.datasetPayload(manifest);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(payload);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(keys.getPublic());
        verifier.update(payload);
        assertThat(verifier.verify(signature)).isTrue();

        var tampered = new ShadowEvaluationManifest.DatasetManifest(
                "dataset", "v2", "schema-v1", 2, "a".repeat(64),
                "artifact-store:datasets/dataset/v1", "signer", "placeholder");
        verifier.initVerify(keys.getPublic());
        verifier.update(ShadowEvaluationSigning.datasetPayload(tampered));
        assertThat(verifier.verify(signature)).isFalse();
        assertThat(Base64.getEncoder().encodeToString(signature)).isNotBlank();
    }
}
