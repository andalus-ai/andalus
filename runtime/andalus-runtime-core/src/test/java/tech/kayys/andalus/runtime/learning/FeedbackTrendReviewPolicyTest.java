package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.harness.learning.FeedbackAggregation;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedbackTrendReviewPolicyTest {

    private final FeedbackAggregationRecoveryService.Subject subject =
            new FeedbackAggregationRecoveryService.Subject(
                    FeedbackAggregationRecoveryService.SubjectKind.TASK, "task-review");

    @Test
    void regressionRequiresHumanReviewAndCarriesReasonsAndSnapshotReferences() {
        var baseline = snapshot("baseline", "2026-11-02T10:00:00Z",
                FeedbackAggregation.Outcome.CONSENSUS_POSITIVE, 0.9, 0.1, 3, 4,
                List.of("s1", "s2", "s3"));
        var current = snapshot("current", "2026-11-02T11:00:00Z",
                FeedbackAggregation.Outcome.CONSENSUS_NEGATIVE, 0.1, 0.9, 3, 4,
                List.of("s2", "s3"));
        var trend = FeedbackTrendAnalyzer.analyze(List.of(baseline, current));

        var assessment = FeedbackTrendReviewPolicy.evaluate(trend,
                FeedbackTrendReviewPolicy.Policy.conservativeDefaults(), current);

        assertThat(assessment.decision())
                .isEqualTo(FeedbackTrendReviewPolicy.Decision.REVIEW_REQUIRED);
        assertThat(assessment.reasons()).contains(
                FeedbackTrendReviewPolicy.Reason.REGRESSION_DETECTED,
                FeedbackTrendReviewPolicy.Reason.CONTRIBUTING_SIGNALS_REMOVED);
        assertThat(assessment.baselineSnapshotId()).isEqualTo("baseline");
        assertThat(assessment.currentSnapshotId()).isEqualTo("current");
        assertThat(assessment.removedSignalCount()).isEqualTo(1);
    }

    @Test
    void emergingDisagreementRequiresHumanReview() {
        var baseline = snapshot("a", "2026-11-02T10:00:00Z",
                FeedbackAggregation.Outcome.CONSENSUS_POSITIVE, 0.9, 0.1, 2, 2,
                List.of("s1", "s2"));
        var current = snapshot("b", "2026-11-02T11:00:00Z",
                FeedbackAggregation.Outcome.CONFLICTED, 0.5, 0.5, 3, 3,
                List.of("s1", "s2", "s3"));
        var trend = FeedbackTrendAnalyzer.analyze(List.of(baseline, current));

        var assessment = FeedbackTrendReviewPolicy.evaluate(trend,
                FeedbackTrendReviewPolicy.Policy.conservativeDefaults(), current);

        assertThat(assessment.decision())
                .isEqualTo(FeedbackTrendReviewPolicy.Decision.REVIEW_REQUIRED);
        assertThat(assessment.reasons())
                .contains(FeedbackTrendReviewPolicy.Reason.EMERGING_DISAGREEMENT);
    }

    @Test
    void stableTrendNeedsNoReviewWhenEvidenceIsSufficientAndNoSignalsWereRemoved() {
        var baseline = snapshot("a", "2026-11-02T10:00:00Z",
                FeedbackAggregation.Outcome.CONSENSUS_POSITIVE, 0.9, 0.1, 2, 2,
                List.of("s1", "s2"));
        var current = snapshot("b", "2026-11-02T11:00:00Z",
                FeedbackAggregation.Outcome.CONSENSUS_POSITIVE, 0.91, 0.09, 3, 3,
                List.of("s1", "s2", "s3"));
        var trend = FeedbackTrendAnalyzer.analyze(List.of(baseline, current));

        var assessment = FeedbackTrendReviewPolicy.evaluate(trend,
                FeedbackTrendReviewPolicy.Policy.conservativeDefaults(), current);

        assertThat(assessment.decision())
                .isEqualTo(FeedbackTrendReviewPolicy.Decision.NO_REVIEW_REQUIRED);
        assertThat(assessment.reasons())
                .containsExactly(FeedbackTrendReviewPolicy.Reason.NO_REVIEW_TRIGGERED);
    }

    @Test
    void insufficientEvidenceOrBelowMinimumCountsIsInconclusive() {
        var baseline = snapshot("a", "2026-11-02T10:00:00Z",
                FeedbackAggregation.Outcome.CONSENSUS_POSITIVE, 0.9, 0.1, 2, 2,
                List.of("s1", "s2"));
        var insufficient = snapshot("b", "2026-11-02T11:00:00Z",
                FeedbackAggregation.Outcome.INSUFFICIENT_EVIDENCE, 0.0, 0.0, 0, 0,
                List.of());
        var trend = FeedbackTrendAnalyzer.analyze(List.of(baseline, insufficient));
        var assessment = FeedbackTrendReviewPolicy.evaluate(trend,
                FeedbackTrendReviewPolicy.Policy.conservativeDefaults(), insufficient);

        assertThat(assessment.decision()).isEqualTo(FeedbackTrendReviewPolicy.Decision.INCONCLUSIVE);
        assertThat(assessment.reasons())
                .containsExactly(FeedbackTrendReviewPolicy.Reason.INSUFFICIENT_EVIDENCE);

        var stable = snapshot("c", "2026-11-02T12:00:00Z",
                FeedbackAggregation.Outcome.CONSENSUS_POSITIVE, 0.9, 0.1, 1, 1,
                List.of("s1"));
        var stableTrend = FeedbackTrendAnalyzer.analyze(List.of(baseline, stable));
        var belowMinimum = FeedbackTrendReviewPolicy.evaluate(stableTrend,
                FeedbackTrendReviewPolicy.Policy.conservativeDefaults(), stable);
        assertThat(belowMinimum.decision()).isEqualTo(FeedbackTrendReviewPolicy.Decision.INCONCLUSIVE);
        assertThat(belowMinimum.reasons()).contains(
                FeedbackTrendReviewPolicy.Reason.BELOW_MINIMUM_SOURCE_COUNT,
                FeedbackTrendReviewPolicy.Reason.BELOW_MINIMUM_EVIDENCE_COUNT);
    }

    @Test
    void rejectsSnapshotFromDifferentSubjectOrTrendVersion() {
        var baseline = snapshot("a", "2026-11-02T10:00:00Z",
                FeedbackAggregation.Outcome.CONSENSUS_POSITIVE, 0.9, 0.1, 2, 2,
                List.of("s1", "s2"));
        var current = snapshot("b", "2026-11-02T11:00:00Z",
                FeedbackAggregation.Outcome.CONSENSUS_POSITIVE, 0.9, 0.1, 2, 2,
                List.of("s1", "s2"));
        var trend = FeedbackTrendAnalyzer.analyze(List.of(baseline, current));
        var mismatched = snapshot("other", "2026-11-02T12:00:00Z",
                FeedbackAggregation.Outcome.CONSENSUS_POSITIVE, 1.0, 0.0, 2, 2,
                List.of("s1", "s2"));

        assertThatThrownBy(() -> FeedbackTrendReviewPolicy.evaluate(trend,
                FeedbackTrendReviewPolicy.Policy.conservativeDefaults(), mismatched))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidPolicyThresholdsAreRejected() {
        assertThatThrownBy(() -> new FeedbackTrendReviewPolicy.Policy(0, 1, 0, true, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeedbackTrendReviewPolicy.Policy(1, 1, -1, true, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private JdbcFeedbackAggregationSnapshotStore.Snapshot snapshot(
            String id, String createdAt, FeedbackAggregation.Outcome outcome,
            double positiveRatio, double negativeRatio, int sources, int evidence,
            List<String> signalIds) {
        FeedbackAggregation.Result result = new FeedbackAggregation.Result(
                subject.key(), outcome, positiveRatio, negativeRatio, 0.0,
                positiveRatio, negativeRatio, sources, evidence, List.of());
        return new JdbcFeedbackAggregationSnapshotStore.Snapshot(id, subject, result,
                "trust", "aggregation", "corroboration", signalIds, signalIds,
                List.of(), List.of(), Instant.parse(createdAt));
    }
}
