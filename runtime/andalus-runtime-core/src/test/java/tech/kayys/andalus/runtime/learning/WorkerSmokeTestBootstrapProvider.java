package tech.kayys.andalus.runtime.learning;

import java.util.Optional;

/**
 * Test-only ServiceLoader bootstrap. It deliberately trusts no signer so process smoke tests
 * exercise a bounded, correlated rejection without embedding private keys or executable artifacts.
 */
public final class WorkerSmokeTestBootstrapProvider
        implements ShadowEvaluationWorkerEntrypoint.BootstrapProvider {

    @Override
    public ShadowEvaluationWorkerEngine createEngine() {
        return new ShadowEvaluationWorkerEngine(
                new ShadowEvaluationWorkerManifestVerifier(signerId -> Optional.empty()),
                (verifiedBytes, descriptor) -> {
                    throw new IllegalStateException("test loader must not receive untrusted bytes");
                },
                new ShadowEvaluationRunner());
    }
}
