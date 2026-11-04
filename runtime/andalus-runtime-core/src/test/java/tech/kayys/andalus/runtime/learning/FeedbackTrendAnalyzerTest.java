package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.harness.learning.FeedbackAggregation;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedbackTrendAnalyzerTest {

    private final FeedbackAggregationRecoveryService.Subject subject =
            new FeedbackAggregationRecoveryService.Subject(
                    FeedbackAggregationRecoveryService.SubjectKind.TASK, "task-1");

    @Test
    void detectsPositiveConsensusTurningNegativeAsRegression() {
        var trend = FeedbackTrendAnalyzer.analyze(List.of(
                snapshot("a", "2026-11-02T10:00:00Z", FeedbackAggregation.Outcome.CONSENSUS_POSITIVE,
                        0.9, 0.1, 2, 2, List.of("s1", "s2")),
                snapshot("b", "2026-11-02T11:00:00Z", FeedbackAggregation.Outcome.CONSENSUS_NEGATIVE,
                        0.1, 0.9, 3, 3, List.of("s2", "s3"))));

        assertThat(trend.classification()).isEqualTo(FeedbackTrendAnalyzer.Classification.REGRESSION);
        assertThat(trend.positiveRatioDelta()).isEqualTo(-0.8);
        assertThat(trend.distinctSourceDelta()).isEqualTo(1);
        assertThat(trend.evidenceReferenceDelta()).isEqualTo(1);
        assertThat(trend.addedSignalIds()).containsExactly("s3");
        assertThat(trend.removedSignalIds()).containsExactly("s1");
    }

    @Test
    void identifiesEmergingDisagreementWithoutCallingItRegression() {
        var trend = FeedbackTrendAnalyzer.analyze(List.of(
                snapshot("a", "2026-11-02T10:00:00Z", FeedbackAggregation.Outcome.CONSENSUS_POSITIVE,
                        0.9, 0.1, 2, 2, List.of("s1", "s2")),
                snapshot("b", "2026-11-02T11:00:00Z", FeedbackAggregation.Outcome.CONFLICTED,
                        0.5, 0.5, 3, 3, List.of("s1", "s2", "s3"))));

        assertThat(trend.classification())
                .isEqualTo(FeedbackTrendAnalyzer.Classification.EMERGING_DISAGREEMENT);
    }

    @Test
    void reportsEvidenceGrowthEvenWhenConsensusDoesNotChange() {
        var trend = FeedbackTrendAnalyzer.analyze(List.of(
                snapshot("a", "2026-11-02T10:00:00Z", FeedbackAggregation.Outcome.CONSENSUS_POSITIVE,
                        0.9, 0.1, 1, 1, List.of("s1")),
                snapshot("b", "2026-11-02T11:00:00Z", FeedbackAggregation.Outcome.CONSENSUS_POSITIVE,
                        0.92, 0.08, 3, 4, List.of("s1", "s2", "s3"))));

        assertThat(trend.classification()).isEqualTo(FeedbackTrendAnalyzer.Classification.STABLE);
        assertThat(trend.distinctSourceDelta()).isEqualTo(2);
        assertThat(trend.evidenceReferenceDelta()).isEqualTo(3);
        assertThat(trend.addedSignalIds()).containsExactly("s2", "s3");
    }

    @Test
    void insufficientEvidenceDoesNotProduceFalseRegression() {
        var trend = FeedbackTrendAnalyzer.analyze(List.of(
                snapshot("a", "2026-11-02T10:00:00Z", FeedbackAggregation.Outcome.CONSENSUS_POSITIVE,
                        0.9, 0.1, 2, 2, List.of("s1", "s2")),
                snapshot("b", "2026-11-02T11:00:00Z", FeedbackAggregation.Outcome.INSUFFICIENT_EVIDENCE,
                        0.0, 0.0, 0, 0, List.of())));

        assertThat(trend.classification())
                .isEqualTo(FeedbackTrendAnalyzer.Classification.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void sortsSnapshotsDeterministicallyAndRequiresOneTypedSubject() {
        var later = snapshot("z", "2026-11-02T11:00:00Z",
                FeedbackAggregation.Outcome.CONSENSUS_POSITIVE, 0.9, 0.1, 2, 2, List.of("s1"));
        var earlier = snapshot("a", "2026-11-02T10:00:00Z",
                FeedbackAggregation.Outcome.CONSENSUS_POSITIVE, 0.9, 0.1, 2, 2, List.of("s1"));
        var trend = FeedbackTrendAnalyzer.analyze(List.of(later, earlier));

        assertThat(trend.baselineSnapshotId()).isEqualTo("a");
        assertThat(trend.currentSnapshotId()).isEqualTo("z");
        assertThat(FeedbackTrendAnalyzer.analyze(List.of(later)).classification())
                .isEqualTo(FeedbackTrendAnalyzer.Classification.INSUFFICIENT_HISTORY);
        assertThatThrownBy(() -> FeedbackTrendAnalyzer.analyze(List.of(later,
                snapshotForOtherSubject("c")))).isInstanceOf(IllegalArgumentException.class);
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

    private JdbcFeedbackAggregationSnapshotStore.Snapshot snapshotForOtherSubject(String id) {
        var other = new FeedbackAggregationRecoveryService.Subject(
                FeedbackAggregationRecoveryService.SubjectKind.GOAL, "task-1");
        FeedbackAggregation.Result result = new FeedbackAggregation.Result(
                other.key(), FeedbackAggregation.Outcome.CONSENSUS_POSITIVE,
                1.0, 0.0, 0.0, 1.0, 0.0, 1, 1, List.of());
        return new JdbcFeedbackAggregationSnapshotStore.Snapshot(id, other, result,
                "trust", "aggregation", "corroboration", List.of(), List.of(),
                List.of(), List.of(), Instant.parse("2026-11-02T12:00:00Z"));
    }
}
