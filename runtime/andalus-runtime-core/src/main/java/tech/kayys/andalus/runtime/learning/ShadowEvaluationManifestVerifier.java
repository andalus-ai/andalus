package tech.kayys.andalus.runtime.learning;

import tech.kayys.andalus.harness.evaluation.Evaluator;

/** Cryptographic trust boundary. Candidate artifacts are verified as bytes, never loaded by the host. */
public interface ShadowEvaluationManifestVerifier {
    boolean verifyDataset(ShadowEvaluationManifest.DatasetManifest manifest, Evaluator.Dataset dataset);

    /** Verifies signature and immutable artifact bytes without constructing an evaluator. */
    boolean verifyEvaluatorArtifact(ShadowEvaluationManifest.EvaluatorArtifactManifest manifest);

    /** Legacy host-loaded adapter verification is deliberately fail-closed. */
    @Deprecated
    default boolean verifyEvaluatorArtifact(
            ShadowEvaluationManifest.EvaluatorArtifactManifest manifest, Evaluator evaluator) {
        return false;
    }
}
