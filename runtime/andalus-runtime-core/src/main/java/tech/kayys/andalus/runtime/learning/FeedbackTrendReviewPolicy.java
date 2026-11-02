package tech.kayys.andalus.runtime.learning;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure advisory guardrails for deciding whether a feedback trend merits human review.
 * The result is a recommendation only; it never dispatches work or changes runtime policy.
 */
public final class FeedbackTrendReviewPolicy {

    public enum Decision {
        REVIEW_REQUIRED,
        NO_REVIEW_REQUIRED,
        INCONCLUSIVE
    }

    public enum Reason {
        REGRESSION_DETECTED,
        EMERGING_DISAGREEMENT,
        CONTRIBUTING_SIGNALS_REMOVED,
        SOURCE_COUNT_DECREASED,
        EVIDENCE_COUNT_DECREASED,
        INSUFFICIENT_HISTORY,
        INSUFFICIENT_EVIDENCE,
        BELOW_MINIMUM_SOURCE_COUNT,
        BELOW_MINIMUM_EVIDENCE_COUNT,
        NO_REVIEW_TRIGGERED
    }

    public record Policy(
            int minimumDistinctSources,
            int minimumEvidenceReferences,
            int minimumRemovedSignalsForReview,
            boolean reviewOnSourceCountDecrease,
            boolean reviewOnEvidenceCountDecrease) {
        public Policy {
            if (minimumDistinctSources < 1 || minimumEvidenceReferences < 1
                    || minimumRemovedSignalsForReview < 0) {
                throw new IllegalArgumentException("review thresholds must be non-negative and evidence minima positive");
            }
        }

        public static Policy conservativeDefaults() {
            return new Policy(2, 2, 1, true, true);
        }
    }

    public record Assessment(
            FeedbackAggregationRecoveryService.Subject subject,
            Decision decision,
            List<Reason> reasons,
            String baselineSnapshotId,
            String currentSnapshotId,
            int distinctSourceDelta,
            int evidenceReferenceDelta,
            int removedSignalCount) {
        public Assessment {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(decision, "decision");
            reasons = List.copyOf(reasons);
        }
    }

    private FeedbackTrendReviewPolicy() {
    }

    /**
     * Evaluates a trend using the current immutable snapshot's absolute evidence counts.
     */
    public static Assessment evaluate(
            FeedbackTrendAnalyzer.Trend trend,
            Policy policy,
            JdbcFeedbackAggregationSnapshotStore.Snapshot currentSnapshot) {
        Objects.requireNonNull(currentSnapshot, "currentSnapshot");
        if (!trend.subject().equals(currentSnapshot.subject())
                || !Objects.equals(trend.currentSnapshotId(), currentSnapshot.id())) {
            throw new IllegalArgumentException("current snapshot must match the trend subject and snapshot id");
        }
        return evaluate(trend, policy, currentSnapshot.aggregation().distinctSources(),
                currentSnapshot.aggregation().evidenceReferences());
    }

    /**
     * Evaluates a trend with explicit absolute source/evidence counts.
     */
    public static Assessment evaluate(
            FeedbackTrendAnalyzer.Trend trend,
            Policy policy,
            int currentDistinctSources,
            int currentEvidenceReferences) {
        Objects.requireNonNull(trend, "trend");
        Objects.requireNonNull(policy, "policy");
        if (currentDistinctSources < 0 || currentEvidenceReferences < 0) {
            throw new IllegalArgumentException("current evidence counts cannot be negative");
        }

        List<Reason> reasons = new ArrayList<>();
        if (trend.classification() == FeedbackTrendAnalyzer.Classification.INSUFFICIENT_HISTORY) {
            reasons.add(Reason.INSUFFICIENT_HISTORY);
            return assessment(trend, Decision.INCONCLUSIVE, reasons);
        }
        if (trend.classification() == FeedbackTrendAnalyzer.Classification.INSUFFICIENT_EVIDENCE) {
            reasons.add(Reason.INSUFFICIENT_EVIDENCE);
            return assessment(trend, Decision.INCONCLUSIVE, reasons);
        }
        if (currentDistinctSources < policy.minimumDistinctSources()) {
            reasons.add(Reason.BELOW_MINIMUM_SOURCE_COUNT);
        }
        if (currentEvidenceReferences < policy.minimumEvidenceReferences()) {
            reasons.add(Reason.BELOW_MINIMUM_EVIDENCE_COUNT);
        }
        if (!reasons.isEmpty()) {
            return assessment(trend, Decision.INCONCLUSIVE, reasons);
        }

        switch (trend.classification()) {
            case REGRESSION -> reasons.add(Reason.REGRESSION_DETECTED);
            case EMERGING_DISAGREEMENT -> reasons.add(Reason.EMERGING_DISAGREEMENT);
            default -> { }
        }
        if (trend.removedSignalIds().size() >= policy.minimumRemovedSignalsForReview()
                && policy.minimumRemovedSignalsForReview() > 0) {
            reasons.add(Reason.CONTRIBUTING_SIGNALS_REMOVED);
        }
        if (policy.reviewOnSourceCountDecrease() && trend.distinctSourceDelta() < 0) {
            reasons.add(Reason.SOURCE_COUNT_DECREASED);
        }
        if (policy.reviewOnEvidenceCountDecrease() && trend.evidenceReferenceDelta() < 0) {
            reasons.add(Reason.EVIDENCE_COUNT_DECREASED);
        }

        if (reasons.isEmpty()) {
            reasons.add(Reason.NO_REVIEW_TRIGGERED);
            return assessment(trend, Decision.NO_REVIEW_REQUIRED, reasons);
        }
        return assessment(trend, Decision.REVIEW_REQUIRED, reasons);
    }

    private static Assessment assessment(
            FeedbackTrendAnalyzer.Trend trend, Decision decision, List<Reason> reasons) {
        return new Assessment(trend.subject(), decision, reasons,
                trend.baselineSnapshotId(), trend.currentSnapshotId(),
                trend.distinctSourceDelta(), trend.evidenceReferenceDelta(),
                trend.removedSignalIds().size());
    }
}
