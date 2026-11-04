package tech.kayys.andalus.runtime.learning;

import tech.kayys.andalus.harness.evaluation.Evaluator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;

/**
 * Runs baseline and candidate evaluators against the same immutable dataset revision.
 * The runner is offline orchestration only; it has no production routing or promotion hooks.
 */
public final class ShadowEvaluationRunner {

    public record MetricRule(String name, ShadowEvaluationReport.Direction direction,
                             double minimumImprovement) {
        public MetricRule {
            if (name == null || name.isBlank() || name.length() > 128
                    || direction == null || !Double.isFinite(minimumImprovement)
                    || minimumImprovement < 0) {
                throw new IllegalArgumentException("invalid shadow metric rule");
            }
        }
    }

    public record RunRequest(
            String runId,
            FeedbackAdaptationGate.Proposal proposal,
            String approvalId,
            String baselineCandidateId,
            String baselineCandidateVersion,
            Evaluator evaluatorForBaseline,
            Evaluator.EvaluatorDescriptor baselineDescriptor,
            String candidateId,
            String candidateVersion,
            Evaluator evaluatorForCandidate,
            Evaluator.EvaluatorDescriptor candidateDescriptor,
            Evaluator.Dataset dataset,
            List<MetricRule> metricRules) {
        public RunRequest {
            required(runId, 36, "runId");
            Objects.requireNonNull(proposal, "proposal");
            required(approvalId, 36, "approvalId");
            required(baselineCandidateId, 255, "baselineCandidateId");
            required(baselineCandidateVersion, 128, "baselineCandidateVersion");
            Objects.requireNonNull(evaluatorForBaseline, "evaluatorForBaseline");
            Objects.requireNonNull(baselineDescriptor, "baselineDescriptor");
            required(candidateId, 255, "candidateId");
            required(candidateVersion, 128, "candidateVersion");
            Objects.requireNonNull(evaluatorForCandidate, "evaluatorForCandidate");
            Objects.requireNonNull(candidateDescriptor, "candidateDescriptor");
            Objects.requireNonNull(dataset, "dataset");
            metricRules = List.copyOf(metricRules);
            if (metricRules.isEmpty()) throw new IllegalArgumentException("at least one metric rule is required");
            if (metricRules.stream().map(MetricRule::name).distinct().count() != metricRules.size()) {
                throw new IllegalArgumentException("metric rule names must be unique");
            }
        }
    }

    public CompletionStage<ShadowEvaluationReport> evaluate(RunRequest request) {
        Objects.requireNonNull(request, "request");
        String datasetFingerprint = fingerprint(request.dataset());
        Evaluator.Runtime baselineRuntime = new Evaluator.DefaultRuntime(request.evaluatorForBaseline());
        Evaluator.Runtime candidateRuntime = new Evaluator.DefaultRuntime(request.evaluatorForCandidate());

        Evaluator.Request baselineRequest = new Evaluator.Request(
                request.runId() + "-b", request.baselineCandidateId(),
                request.baselineCandidateVersion(), request.dataset(), request.baselineDescriptor());
        Evaluator.Request candidateRequest = new Evaluator.Request(
                request.runId() + "-c", request.candidateId(),
                request.candidateVersion(), request.dataset(), request.candidateDescriptor());

        // Sequential execution prevents overlap between two potentially stateful evaluator adapters.
        return baselineRuntime.evaluate(baselineRequest).thenCompose(baseline ->
                candidateRuntime.evaluate(candidateRequest).thenApply(candidate -> {
                    validateComparableReports(baseline, candidate, request.dataset());
                    validateMetricCoverage(baseline, candidate, request.dataset(), request.metricRules());
                    List<ShadowEvaluationReport.Metric> metrics = new ArrayList<>();
                    for (MetricRule rule : request.metricRules()) {
                        Double baselineValue = baseline.aggregateMetrics().get(rule.name());
                        Double candidateValue = candidate.aggregateMetrics().get(rule.name());
                        if (baselineValue == null || candidateValue == null) {
                            throw new IllegalStateException("required metric missing from baseline or candidate: " + rule.name());
                        }
                        metrics.add(new ShadowEvaluationReport.Metric(rule.name(), baselineValue,
                                candidateValue, rule.direction(), rule.minimumImprovement()));
                    }
                    ShadowEvaluationReport.Status status = metrics.stream()
                            .allMatch(ShadowEvaluationReport.Metric::passes)
                            ? ShadowEvaluationReport.Status.PASS : ShadowEvaluationReport.Status.FAIL;
                    String baselineEvidence = evidenceReference("baseline", baseline);
                    String candidateEvidence = evidenceReference("candidate", candidate);
                    FeedbackAdaptationGate.Proposal proposal = request.proposal();
                    return new ShadowEvaluationReport(
                            request.runId(), proposal.id(), proposal.subject(), proposal.sourceSnapshotId(),
                            request.approvalId(), proposal.policyFingerprint(),
                            baseline.evaluatorId(), baseline.evaluatorVersion(), baseline.evaluatorConfigFingerprint(),
                            candidate.evaluatorId(), candidate.evaluatorVersion(), candidate.evaluatorConfigFingerprint(),
                            baseline.candidateId(), baseline.candidateVersion(),
                            candidate.candidateId(), candidate.candidateVersion(),
                            request.dataset().id(), request.dataset().version(), datasetFingerprint,
                            status, metrics, List.of(baselineEvidence, candidateEvidence),
                            baseline.completedAt().isAfter(candidate.completedAt())
                                    ? baseline.completedAt() : candidate.completedAt());
                }));
    }

    private static void validateComparableReports(Evaluator.Report baseline, Evaluator.Report candidate,
                                                    Evaluator.Dataset dataset) {
        if (!baseline.datasetId().equals(dataset.id()) || !candidate.datasetId().equals(dataset.id())
                || !baseline.datasetVersion().equals(dataset.version())
                || !candidate.datasetVersion().equals(dataset.version())) {
            throw new IllegalStateException("baseline and candidate must use the requested dataset revision");
        }
        if (baseline.results().size() != dataset.cases().size()
                || candidate.results().size() != dataset.cases().size()) {
            throw new IllegalStateException("baseline and candidate must produce one result per dataset case");
        }
    }

    private static void validateMetricCoverage(Evaluator.Report baseline, Evaluator.Report candidate,
                                                Evaluator.Dataset dataset, List<MetricRule> rules) {
        for (int index = 0; index < dataset.cases().size(); index++) {
            String caseId = dataset.cases().get(index).id();
            Evaluator.CaseResult baselineResult = baseline.results().get(index);
            Evaluator.CaseResult candidateResult = candidate.results().get(index);
            for (MetricRule rule : rules) {
                if (!baselineResult.metrics().containsKey(rule.name())
                        || !candidateResult.metrics().containsKey(rule.name())) {
                    throw new IllegalStateException("required metric " + rule.name()
                            + " is missing for case " + caseId + " on baseline or candidate");
                }
            }
        }
    }

    /** Hashes case inputs and expected outputs without persisting or returning their contents. */
    static String fingerprint(Evaluator.Dataset dataset) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, dataset.id());
            update(digest, dataset.version());
            for (Evaluator.EvaluationCase item : dataset.cases()) {
                update(digest, item.id());
                update(digest, item.input());
                update(digest, item.expectedOutput() == null ? "expected-output:null" : "expected-output:value");
                if (item.expectedOutput() != null) update(digest, item.expectedOutput());
                new TreeMap<>(item.metadata()).forEach((key, value) -> {
                    update(digest, key);
                    update(digest, value);
                });
                update(digest, "case-end");
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Content digest excludes evaluator details and raw case inputs/outputs. */
    private static String evidenceReference(String side, Evaluator.Report report) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, report.evaluationId());
            update(digest, report.candidateId());
            update(digest, report.candidateVersion());
            update(digest, report.datasetId());
            update(digest, report.datasetVersion());
            update(digest, report.evaluatorId());
            update(digest, report.evaluatorVersion());
            update(digest, report.evaluatorConfigFingerprint());
            for (Evaluator.CaseResult result : report.results()) {
                update(digest, result.caseId());
                update(digest, Boolean.toString(result.passed()));
                new TreeMap<>(result.metrics()).forEach((key, value) -> {
                    update(digest, key);
                    update(digest, Double.toHexString(value));
                });
            }
            return "shadow-evaluation:" + side + ":" + report.evaluationId()
                    + ":sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static String required(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " must be non-blank and at most " + max + " characters");
        }
        return value;
    }
}
