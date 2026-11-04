package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShadowEvaluationManifestTest {
    @Test
    void requiresSha256DatasetFingerprint() {
        String digest = "a".repeat(64);
        var manifest = new ShadowEvaluationManifest.DatasetManifest(
                "validation", "v1", "case-v1", 2, digest,
                "dataset://validation/v1", "signer", "signature");
        assertThat(manifest.contentFingerprint()).isEqualTo(digest);
        assertThatThrownBy(() -> new ShadowEvaluationManifest.DatasetManifest(
                "validation", "v1", "case-v1", 2, "not-a-digest",
                "dataset://validation/v1", "signer", "signature"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
