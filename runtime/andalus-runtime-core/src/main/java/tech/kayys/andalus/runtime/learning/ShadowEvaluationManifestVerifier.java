package tech.kayys.andalus.runtime.learning;

import tech.kayys.andalus.harness.evaluation.Evaluator;

/**
 * Cryptographic trust boundary for signed evaluation inputs.
 *
 * Production implementations must verify signatures against an explicitly managed trust store.
 * Dataset verification binds the signature to the manifest and supplied dataset fingerprint.
 * Evaluator verification must bind the signed artifact digest to the actual supplied adapter instance
 * (for example, through a trusted artifact loader that records adapter provenance). Merely checking
 * manifest metadata while accepting an arbitrary evaluator instance is not sufficient.
 */
public interface ShadowEvaluationManifestVerifier {
    boolean verifyDataset(
            ShadowEvaluationManifest.DatasetManifest manifest,
            Evaluator.Dataset dataset);

    boolean verifyEvaluatorArtifact(
            ShadowEvaluationManifest.EvaluatorArtifactManifest manifest,
            Evaluator evaluator);
}
