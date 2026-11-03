package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.harness.evaluation.Evaluator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShadowEvaluationRunnerTest {

    @Test
    void evaluatesBaselineAndCandidateAgainstSameDatasetAndProducesBoundEvidence() {
        Evaluator.Dataset dataset = dataset();
        AtomicInteger calls = new AtomicInteger();
        Evaluator baseline = item -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new Evaluator.CaseResult(
                    item.id(), Map.of("quality", 0.80), true, "must-not-be-persisted"));
        };
        Evaluator candidate = item -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new Evaluator.CaseResult(
                    item.id(), Map.of("quality", 0.90), true, "must-not-be-persisted"));
        };

        ShadowEvaluationReport report = new ShadowEvaluationRunner().evaluate(request(
                dataset, baseline, candidate, List.of(new ShadowEvaluationRunner.MetricRule(
                        "quality", ShadowEvaluationReport.Direction.HIGHER_IS_BETTER, 0.05))))
                .toCompletableFuture().join();

        assertThat(calls).hasValue(4);
        assertThat(report.status()).isEqualTo(ShadowEvaluationReport.Status.PASS);
        assertThat(report.metrics()).singleElement().satisfies(metric -> {
            assertThat(metric.baselineValue()).isEqualTo(0.80);
            assertThat(metric.candidateValue()).isEqualTo(0.90);
            assertThat(metric.passes()).isTrue();
        });
        assertThat(report.datasetFingerprint()).hasSize(64);
        assertThat(report.evidenceReferences()).hasSize(2)
                .allMatch(value -> value.contains(":sha256:"));
        assertThat(report.evidenceReferences().toString()).doesNotContain("must-not-be-persisted");
        assertThat(report.proposalId()).isEqualTo("proposal-1");
        assertThat(report.approvalId()).isEqualTo("approval-1");
        assertThat(report.baselineCandidateId()).isEqualTo("baseline-model");
        assertThat(report.baselineCandidateVersion()).isEqualTo("v1");
        assertThat(report.candidateId()).isEqualTo("candidate-model");
        assertThat(report.candidateVersion()).isEqualTo("v2");
    }

    @Test
    void reportsFailWhenCandidateMissesDirectionalImprovement() {
        ShadowEvaluationReport report = new ShadowEvaluationRunner().evaluate(request(
                dataset(),
                item -> CompletableFuture.completedFuture(new Evaluator.CaseResult(
                        item.id(), Map.of("latency", 100.0), true, null)),
                item -> CompletableFuture.completedFuture(new Evaluator.CaseResult(
                        item.id(), Map.of("latency", 110.0), true, null)),
                List.of(new ShadowEvaluationRunner.MetricRule(
                        "latency", ShadowEvaluationReport.Direction.LOWER_IS_BETTER, 0))))
                .toCompletableFuture().join();

        assertThat(report.status()).isEqualTo(ShadowEvaluationReport.Status.FAIL);
        assertThat(report.metrics().get(0).passes()).isFalse();
    }

    @Test
    void rejectsMissingComparisonMetricsRatherThanProducingMisleadingPass() {
        var stage = new ShadowEvaluationRunner().evaluate(request(
                dataset(),
                item -> CompletableFuture.completedFuture(new Evaluator.CaseResult(
                        item.id(), Map.of("quality", 0.8), true, null)),
                item -> CompletableFuture.completedFuture(new Evaluator.CaseResult(
                        item.id(), Map.of("other", 0.9), true, null)),
                List.of(new ShadowEvaluationRunner.MetricRule(
                        "quality", ShadowEvaluationReport.Direction.HIGHER_IS_BETTER, 0))));
        assertThatThrownBy(() -> stage.toCompletableFuture().join())
                .isInstanceOf(java.util.concurrent.CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }


    @Test
    void rejectsMetricMissingFromOneCaseEvenWhenAggregateContainsIt() {
        AtomicInteger index = new AtomicInteger();
        Evaluator baseline = item -> {
            int current = index.getAndIncrement();
            return CompletableFuture.completedFuture(new Evaluator.CaseResult(item.id(),
                    current == 0 ? Map.of("quality", 0.8) : Map.of(), true, null));
        };
        Evaluator candidate = item -> CompletableFuture.completedFuture(new Evaluator.CaseResult(
                item.id(), Map.of("quality", 0.9), true, null));

        assertThatThrownBy(() -> new ShadowEvaluationRunner().evaluate(request(
                dataset(), baseline, candidate, List.of(new ShadowEvaluationRunner.MetricRule(
                        "quality", ShadowEvaluationReport.Direction.HIGHER_IS_BETTER, 0))))
                .toCompletableFuture().join())
                .isInstanceOf(java.util.concurrent.CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void datasetFingerprintChangesWhenAnyCaseInputChanges() {
        Evaluator.Dataset original = dataset();
        Evaluator.Dataset changed = new Evaluator.Dataset("validation", "v3", List.of(
                new Evaluator.EvaluationCase("case-1", "changed input", "expected-1", Map.of("locale", "en")),
                new Evaluator.EvaluationCase("case-2", "input-2", "expected-2", Map.of("locale", "en"))));

        assertThat(ShadowEvaluationRunner.fingerprint(original))
                .isNotEqualTo(ShadowEvaluationRunner.fingerprint(changed));
        assertThat(ShadowEvaluationRunner.fingerprint(original))
                .isEqualTo(ShadowEvaluationRunner.fingerprint(dataset()));
    }

    private static ShadowEvaluationRunner.RunRequest request(
            Evaluator.Dataset dataset, Evaluator baseline, Evaluator candidate,
            List<ShadowEvaluationRunner.MetricRule> rules) {
        return new ShadowEvaluationRunner.RunRequest(
                "run-1", proposal(), "approval-1",
                "baseline-model", "v1", baseline,
                new Evaluator.EvaluatorDescriptor("baseline-adapter", "1", Map.of("temperature", "0")),
                "candidate-model", "v2", candidate,
                new Evaluator.EvaluatorDescriptor("candidate-adapter", "2", Map.of("temperature", "0")),
                dataset, rules);
    }

    private static Evaluator.Dataset dataset() {
        return new Evaluator.Dataset("validation", "v3", List.of(
                new Evaluator.EvaluationCase("case-1", "input-1", "expected-1", Map.of("locale", "en")),
                new Evaluator.EvaluationCase("case-2", "input-2", "expected-2", Map.of("locale", "en"))));
    }

    private static FeedbackAdaptationGate.Proposal proposal() {
        return new FeedbackAdaptationGate.Proposal("proposal-1",
                new FeedbackAggregationRecoveryService.Subject(
                        FeedbackAggregationRecoveryService.SubjectKind.TASK, "task-1"),
                "snapshot-1", "policy-fingerprint");
    }
}
