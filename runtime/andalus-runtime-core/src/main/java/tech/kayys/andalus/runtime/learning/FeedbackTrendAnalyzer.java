package tech.kayys.andalus.runtime.learning;

import tech.kayys.andalus.harness.learning.FeedbackAggregation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministically compares immutable advisory snapshots.
 *
 * <p>This class reports changes only. It does not alter admission, training,
 * routing, promotion, or deployment behavior.</p>
 */
public final class FeedbackTrendAnalyzer {

    private static final double DEFAULT_MINIMUM_POSITIVE_RATIO_DROP = 0.05d;

    public enum Classification {
        INSUFFICIENT_HISTORY,
        INSUFFICIENT_EVIDENCE,
        REGRESSION,
        EMERGING_DISAGREEMENT,
        IMPROVEMENT,
        STABLE
    }

    public record Trend(
            FeedbackAggregationRecoveryService.Subject subject,
            Classification classification,
            String baselineSnapshotId,
            String currentSnapshotId,
            FeedbackAggregation.Outcome baselineOutcome,
            FeedbackAggregation.Outcome currentOutcome,
            double positiveRatioDelta,
            double negativeRatioDelta,
            double positiveWeightDelta,
            double negativeWeightDelta,
            double ambiguousWeightDelta,
            int distinctSourceDelta,
            int evidenceReferenceDelta,
            List<String> addedSignalIds,
            List<String> removedSignalIds
    ) {
        public Trend {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(classification, "classification");
            addedSignalIds = List.copyOf(addedSignalIds);
            removedSignalIds = List.copyOf(removedSignalIds);
        }

        public boolean hasComparison() {
            return baselineSnapshotId != null && currentSnapshotId != null;
        }
    }

    private FeedbackTrendAnalyzer() {
    }

    public static Trend analyze(List<JdbcFeedbackAggregationSnapshotStore.Snapshot> history) {
        return analyze(history, DEFAULT_MINIMUM_POSITIVE_RATIO_DROP);
    }

    /**
     * Compares the two newest snapshots. The threshold applies only to a drop
     * between successive positive-consensus snapshots.
     */
    public static Trend analyze(
            List<JdbcFeedbackAggregationSnapshotStore.Snapshot> history,
            double minimumPositiveRatioDrop) {
        Objects.requireNonNull(history, "history");
        if (!Double.isFinite(minimumPositiveRatioDrop)
                || minimumPositiveRatioDrop < 0.0d || minimumPositiveRatioDrop > 1.0d) {
            throw new IllegalArgumentException("minimum positive ratio drop must be between 0 and 1");
        }
        if (history.isEmpty()) {
            throw new IllegalArgumentException("at least one snapshot is required");
        }

        List<JdbcFeedbackAggregationSnapshotStore.Snapshot> ordered = new ArrayList<>(history);
        ordered.sort(Comparator
                .comparing(JdbcFeedbackAggregationSnapshotStore.Snapshot::createdAt)
                .thenComparing(JdbcFeedbackAggregationSnapshotStore.Snapshot::id));

        JdbcFeedbackAggregationSnapshotStore.Snapshot current = ordered.get(ordered.size() - 1);
        for (JdbcFeedbackAggregationSnapshotStore.Snapshot snapshot : ordered) {
            if (!current.subject().equals(snapshot.subject())) {
                throw new IllegalArgumentException("trend history must contain exactly one typed subject");
            }
        }

        if (ordered.size() < 2) {
            return new Trend(current.subject(), Classification.INSUFFICIENT_HISTORY,
                    null, current.id(), null, current.aggregation().outcome(),
                    0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0, 0,
                    List.of(), List.of());
        }

        JdbcFeedbackAggregationSnapshotStore.Snapshot baseline = ordered.get(ordered.size() - 2);
        FeedbackAggregation.Outcome before = baseline.aggregation().outcome();
        FeedbackAggregation.Outcome after = current.aggregation().outcome();
        double positiveDelta = current.aggregation().positiveRatio()
                - baseline.aggregation().positiveRatio();
        double negativeDelta = current.aggregation().negativeRatio()
                - baseline.aggregation().negativeRatio();

        Classification classification = classify(before, after, positiveDelta,
                minimumPositiveRatioDrop);
        Set<String> beforeSignals = new TreeSet<>(baseline.signalIds());
        Set<String> afterSignals = new TreeSet<>(current.signalIds());
        Set<String> added = new TreeSet<>(afterSignals);
        added.removeAll(beforeSignals);
        Set<String> removed = new TreeSet<>(beforeSignals);
        removed.removeAll(afterSignals);

        return new Trend(current.subject(), classification, baseline.id(), current.id(),
                before, after, positiveDelta, negativeDelta,
                current.aggregation().positiveWeight() - baseline.aggregation().positiveWeight(),
                current.aggregation().negativeWeight() - baseline.aggregation().negativeWeight(),
                current.aggregation().ambiguousWeight() - baseline.aggregation().ambiguousWeight(),
                current.aggregation().distinctSources() - baseline.aggregation().distinctSources(),
                current.aggregation().evidenceReferences() - baseline.aggregation().evidenceReferences(),
                List.copyOf(added), List.copyOf(removed));
    }

    private static Classification classify(
            FeedbackAggregation.Outcome before,
            FeedbackAggregation.Outcome after,
            double positiveRatioDelta,
            double minimumPositiveRatioDrop) {
        if (after == FeedbackAggregation.Outcome.INSUFFICIENT_EVIDENCE) {
            return Classification.INSUFFICIENT_EVIDENCE;
        }
        if (after == FeedbackAggregation.Outcome.CONFLICTED
                && before != FeedbackAggregation.Outcome.CONFLICTED) {
            return Classification.EMERGING_DISAGREEMENT;
        }
        if (before == FeedbackAggregation.Outcome.CONSENSUS_POSITIVE
                && (after == FeedbackAggregation.Outcome.CONSENSUS_NEGATIVE
                || (after == FeedbackAggregation.Outcome.CONSENSUS_POSITIVE
                && positiveRatioDelta <= -minimumPositiveRatioDrop))) {
            return Classification.REGRESSION;
        }
        if (before == FeedbackAggregation.Outcome.CONSENSUS_NEGATIVE
                && after == FeedbackAggregation.Outcome.CONSENSUS_POSITIVE) {
            return Classification.IMPROVEMENT;
        }
        return Classification.STABLE;
    }
}
