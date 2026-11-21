package tech.kayys.andalus.runtime.learning;

import java.nio.file.Path;

/**
 * Image-baked, one-request worker bootstrap. Trust material is read once from a deployment-owned
 * read-only directory; evaluator code is loaded only after the worker engine verifies signatures
 * and exact artifact digests.
 */
public final class DefaultShadowEvaluationWorkerBootstrapProvider
        implements ShadowEvaluationWorkerEntrypoint.BootstrapProvider {

    static final String TRUST_DIRECTORY_ENV = "ANDALUS_SHADOW_TRUST_DIRECTORY";
    static final String DEFAULT_TRUST_DIRECTORY = "/opt/andalus/trust";

    @Override
    public ShadowEvaluationWorkerEngine createEngine() throws Exception {
        String configured = System.getenv(TRUST_DIRECTORY_ENV);
        Path trustDirectory = Path.of(configured == null || configured.isBlank()
                ? DEFAULT_TRUST_DIRECTORY : configured).toAbsolutePath().normalize();
        FileShadowEvaluationWorkerTrustStore trustStore =
                new FileShadowEvaluationWorkerTrustStore(trustDirectory);
        IsolatedJarEvaluatorLoader artifactLoader = new IsolatedJarEvaluatorLoader();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { artifactLoader.close(); }
            catch (Exception ignored) { }
        }, "andalus-shadow-worker-cleanup"));
        return new ShadowEvaluationWorkerEngine(
                new ShadowEvaluationWorkerManifestVerifier(trustStore),
                artifactLoader,
                new ShadowEvaluationRunner());
    }
}
