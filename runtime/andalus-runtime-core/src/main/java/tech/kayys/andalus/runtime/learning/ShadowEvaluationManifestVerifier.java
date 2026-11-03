package tech.kayys.andalus.runtime.learning;

import tech.kayys.andalus.harness.evaluation.Evaluator;

/**
 * Cryptographic trust boundary for signed evaluation inputs.
 *
 * Production implementations must verify signatures against an explicitly managed trust store.
 * Dataset verification must bind the signature to the manifest and supplied dataset fingerprint.
 * Artifact verification must verify both the signed manifest and the bytes fetched from its immutable
 * artifact reference against artifactDigest. Returning true based only on metadata equality is unsafe.
 */
public interface ShadowEvaluationManifestVerifier {
    boolean verifyDataset(
            ShadowEvaluationManifest.DatasetManifest manifest,
            Evaluator.Dataset dataset);

    boolean verifyEvaluatorArtifact(
            ShadowEvaluationManifest.EvaluatorArtifactManifest manifest);
}
