package tech.kayys.andalus.runtime.learning;

import tech.kayys.andalus.harness.evaluation.Evaluator;

import java.io.InputStream;
import java.util.Objects;

/**
 * Resolves immutable artifacts from an allow-listed artifact store, never arbitrary manifest URLs.
 * The returned evaluator must be the exact instance loaded from the verified artifact bytes.
 */
public interface ShadowEvaluationArtifactResolver {
    ResolvedArtifact resolve(String immutableReference);

    record ResolvedArtifact(InputStream content, Evaluator evaluator) implements AutoCloseable {
        public ResolvedArtifact {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(evaluator, "evaluator");
        }
        @Override public void close() throws java.io.IOException { content.close(); }
    }
}
