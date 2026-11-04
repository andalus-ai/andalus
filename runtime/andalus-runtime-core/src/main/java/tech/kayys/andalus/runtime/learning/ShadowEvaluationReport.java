package tech.kayys.andalus.runtime.learning;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, payload-free evidence from one offline shadow evaluation.
 * Dataset contents and prompts are deliberately excluded; only stable identities and fingerprints belong here.
 */
public record ShadowEvaluationReport(
        String runId,
        String proposalId,
        FeedbackAggregationRecoveryService.Subject subject,
        String sourceSnapshotId,
        String approvalId,
        String policyFingerprint,
        String baselineEvaluatorId,
        String baselineEvaluatorVersion,
        String baselineEvaluatorConfigFingerprint,
        String candidateEvaluatorId,
        String candidateEvaluatorVersion,
        String candidateEvaluatorConfigFingerprint,
        String baselineCandidateId,
        String baselineCandidateVersion,
        String candidateId,
        String candidateVersion,
        String datasetId,
        String datasetVersion,
        String datasetFingerprint,
        Status status,
        List<Metric> metrics,
        List<String> evidenceReferences,
        Instant evaluatedAt) {

    public enum Status { PASS, FAIL, INCONCLUSIVE }
    public enum Direction { HIGHER_IS_BETTER, LOWER_IS_BETTER }

    /** requiredImprovement is the minimum directional delta needed to pass. */
    public record Metric(String name, double baselineValue, double candidateValue,
                         Direction direction, double requiredImprovement) {
        public Metric {
            required(name, 128, "metric name");
            Objects.requireNonNull(direction, "direction");
            finite(baselineValue, "baselineValue");
            finite(candidateValue, "candidateValue");
            finite(requiredImprovement, "requiredImprovement");
            if (requiredImprovement < 0) throw new IllegalArgumentException("requiredImprovement cannot be negative");
        }
        public boolean passes() {
            double improvement = direction == Direction.HIGHER_IS_BETTER
                    ? candidateValue - baselineValue : baselineValue - candidateValue;
            return improvement >= requiredImprovement;
        }
    }

    public ShadowEvaluationReport {
        required(runId, 36, "runId");
        required(proposalId, 255, "proposalId");
        Objects.requireNonNull(subject, "subject");
        required(sourceSnapshotId, 36, "sourceSnapshotId");
        required(approvalId, 36, "approvalId");
        required(policyFingerprint, 128, "policyFingerprint");
        required(baselineEvaluatorId, 255, "baselineEvaluatorId");
        required(baselineEvaluatorVersion, 128, "baselineEvaluatorVersion");
        required(baselineEvaluatorConfigFingerprint, 128, "baselineEvaluatorConfigFingerprint");
        required(candidateEvaluatorId, 255, "candidateEvaluatorId");
        required(candidateEvaluatorVersion, 128, "candidateEvaluatorVersion");
        required(candidateEvaluatorConfigFingerprint, 128, "candidateEvaluatorConfigFingerprint");
        required(baselineCandidateId, 255, "baselineCandidateId");
        required(baselineCandidateVersion, 128, "baselineCandidateVersion");
        required(candidateId, 255, "candidateId");
        required(candidateVersion, 128, "candidateVersion");
        required(datasetId, 255, "datasetId");
        required(datasetVersion, 128, "datasetVersion");
        required(datasetFingerprint, 128, "datasetFingerprint");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        metrics = List.copyOf(metrics);
        evidenceReferences = List.copyOf(evidenceReferences);
        if (subject.id().length() > 255) throw new IllegalArgumentException("subject id exceeds 255 characters");
        if (metrics.size() > 256) throw new IllegalArgumentException("too many metrics");
        if (metrics.stream().map(Metric::name).distinct().count() != metrics.size()) {
            throw new IllegalArgumentException("metric names must be unique within a report");
        }
        if (evidenceReferences.size() > 2048
                || evidenceReferences.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 2048)
                || evidenceReferences.stream().distinct().count() != evidenceReferences.size()) {
            throw new IllegalArgumentException("evidence references must be unique, non-blank, and at most 2048 characters");
        }
        Status derived = metrics.isEmpty() ? Status.INCONCLUSIVE
                : metrics.stream().allMatch(Metric::passes) ? Status.PASS
                : Status.FAIL;
        if (status != derived) throw new IllegalArgumentException("status does not match metric outcomes; expected " + derived);
    }

    private static String required(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " must be non-blank and at most " + max + " characters");
        }
        return value;
    }

    private static void finite(double value, String field) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
    }
}
