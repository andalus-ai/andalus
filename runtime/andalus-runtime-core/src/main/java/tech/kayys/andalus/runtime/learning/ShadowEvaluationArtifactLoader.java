package tech.kayys.andalus.runtime.learning;

import tech.kayys.andalus.harness.evaluation.Evaluator;

/**
 * Deployment-owned isolated loader. It must construct the evaluator from the supplied verified
 * bytes, without resolving mutable dependencies or executing untrusted code in the main process.
 */
@FunctionalInterface
public interface ShadowEvaluationArtifactLoader {
    Evaluator load(String immutableReference, byte[] artifactBytes) throws Exception;
}
