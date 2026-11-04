package tech.kayys.andalus.runtime.learning;

import java.util.Objects;

/** Immutable, signed metadata for datasets and evaluator artifacts admitted to offline shadow evaluation. */
public final class ShadowEvaluationManifest {
    private ShadowEvaluationManifest() { }

    public record DatasetManifest(
            String datasetId,
            String datasetVersion,
            String schemaVersion,
            int caseCount,
            String contentFingerprint,
            String sourceReference,
            String signerId,
            String signature) {
        public DatasetManifest {
            required(datasetId, 255, "datasetId");
            required(datasetVersion, 128, "datasetVersion");
            required(schemaVersion, 128, "schemaVersion");
            if (caseCount < 1) throw new IllegalArgumentException("caseCount must be positive");
            fingerprint(contentFingerprint, "contentFingerprint");
            required(sourceReference, 2048, "sourceReference");
            required(signerId, 255, "signerId");
            required(signature, 4096, "signature");
        }
    }

    /** Binds one immutable evaluator artifact to the exact candidate and descriptor configuration. */
    public record EvaluatorArtifactManifest(
            String artifactId,
            String artifactVersion,
            String artifactReference,
            String artifactDigest,
            String candidateId,
            String candidateVersion,
            String evaluatorId,
            String evaluatorVersion,
            String configurationFingerprint,
            String signerId,
            String signature) {
        public EvaluatorArtifactManifest {
            required(artifactId, 255, "artifactId");
            required(artifactVersion, 128, "artifactVersion");
            required(artifactReference, 2048, "artifactReference");
            fingerprint(artifactDigest, "artifactDigest");
            required(candidateId, 255, "candidateId");
            required(candidateVersion, 128, "candidateVersion");
            required(evaluatorId, 255, "evaluatorId");
            required(evaluatorVersion, 128, "evaluatorVersion");
            fingerprint(configurationFingerprint, "configurationFingerprint");
            required(signerId, 255, "signerId");
            required(signature, 4096, "signature");
        }
    }

    private static void fingerprint(String value, String field) {
        required(value, 64, field);
        if (value.length() != 64 || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 hex digest");
        }
    }

    private static void required(String value, int max, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " must be non-blank and at most " + max + " characters");
        }
    }
}
