package tech.kayys.andalus.runtime.learning;

import java.util.Optional;

/**
 * Trusted, immutable manifest lookup boundary. Production implementations must resolve exact versions
 * and must not silently fall back to a mutable "latest" manifest.
 */
public interface ShadowEvaluationTrustRegistry {
    Optional<ShadowEvaluationManifest.DatasetManifest> findDataset(String datasetId, String datasetVersion);

    Optional<ShadowEvaluationManifest.EvaluatorArtifactManifest> findEvaluatorArtifact(
            String candidateId, String candidateVersion, String evaluatorId,
            String evaluatorVersion, String configurationFingerprint);
}
