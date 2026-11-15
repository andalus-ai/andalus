package tech.kayys.andalus.runtime.learning;

import java.io.InputStream;
import java.util.Objects;

/**
 * Resolves immutable evaluator artifact bytes only. Implementations must never load classes,
 * instantiate evaluators, or execute artifact code in the application process.
 */
public interface ShadowEvaluationArtifactContentResolver {
    ResolvedContent resolve(String immutableReference);

    record ResolvedContent(InputStream content) implements AutoCloseable {
        public ResolvedContent {
            Objects.requireNonNull(content, "content");
        }
        @Override public void close() throws java.io.IOException { content.close(); }
    }
}
