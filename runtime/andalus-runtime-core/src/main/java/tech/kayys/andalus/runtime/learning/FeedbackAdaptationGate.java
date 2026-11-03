package tech.kayys.andalus.runtime.learning;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Fail-closed gate from a persisted human disposition to shadow evaluation eligibility.
 * Eligibility is not approval for production activation or deployment.
 */
public final class FeedbackAdaptationGate {

    public enum BlockReason {
        REVIEW_NOT_FOUND,
        REVIEW_NOT_ACCEPTED,
        INCONCLUSIVE_ASSESSMENT,
        SUBJECT_MISMATCH,
        SNAPSHOT_MISMATCH,
        REVIEWER_IDENTITY_MISMATCH,
        INVALID_PROPOSAL
    }

    public record Proposal(
            String id,
            FeedbackAggregationRecoveryService.Subject subject,
            String sourceSnapshotId,
            String policyFingerprint) {
        public Proposal {
            if (id == null || id.isBlank() || subject == null
                    || sourceSnapshotId == null || sourceSnapshotId.isBlank()
                    || policyFingerprint == null || policyFingerprint.isBlank()) {
                throw new IllegalArgumentException("proposal id, subject, source snapshot and policy fingerprint are required");
            }
            if (id.length() > 255 || subject.id().length() > 255 || sourceSnapshotId.length() > 36
                    || policyFingerprint.length() > 128) {
                throw new IllegalArgumentException("proposal field exceeds its supported length");
            }
        }
    }

    public record Result(
            String proposalId,
            boolean eligibleForShadowEvaluation,
            List<BlockReason> blockReasons,
            String reviewId,
            String reviewerId,
            String sourceSnapshotId) {
        public Result {
            Objects.requireNonNull(proposalId, "proposalId");
            blockReasons = List.copyOf(blockReasons);
            if (eligibleForShadowEvaluation && !blockReasons.isEmpty()) {
                throw new IllegalArgumentException("eligible result cannot contain block reasons");
            }
            if (!eligibleForShadowEvaluation && blockReasons.isEmpty()) {
                throw new IllegalArgumentException("blocked result must contain a reason");
            }
        }
    }

    private FeedbackAdaptationGate() {
    }

    /**
     * Checks a stored review against the proposal and the caller's authenticated principal.
     * The caller must obtain authenticatedReviewerId from its trusted security context.
     */
    public static Result evaluate(
            Proposal proposal,
            Optional<JdbcFeedbackTrendReviewStore.Review> storedReview,
            String authenticatedReviewerId) {
        Objects.requireNonNull(proposal, "proposal");
        List<BlockReason> reasons = new ArrayList<>();
        if (storedReview == null || storedReview.isEmpty()) {
            reasons.add(BlockReason.REVIEW_NOT_FOUND);
            return blocked(proposal, reasons, null);
        }

        JdbcFeedbackTrendReviewStore.Review review = storedReview.get();
        if (!review.subject().equals(proposal.subject())) reasons.add(BlockReason.SUBJECT_MISMATCH);
        if (!review.currentSnapshotId().equals(proposal.sourceSnapshotId())) {
            reasons.add(BlockReason.SNAPSHOT_MISMATCH);
        }
        if (review.disposition() != JdbcFeedbackTrendReviewStore.Disposition.ACCEPTED) {
            reasons.add(BlockReason.REVIEW_NOT_ACCEPTED);
        }
        if (review.assessmentDecision() == FeedbackTrendReviewPolicy.Decision.INCONCLUSIVE) {
            reasons.add(BlockReason.INCONCLUSIVE_ASSESSMENT);
        }
        if (authenticatedReviewerId == null || authenticatedReviewerId.isBlank()
                || !review.reviewerId().equals(authenticatedReviewerId)) {
            reasons.add(BlockReason.REVIEWER_IDENTITY_MISMATCH);
        }
        if (reasons.isEmpty()) {
            return new Result(proposal.id(), true, List.of(), review.id(),
                    review.reviewerId(), proposal.sourceSnapshotId());
        }
        return blocked(proposal, reasons, review);
    }

    private static Result blocked(Proposal proposal, List<BlockReason> reasons,
                                  JdbcFeedbackTrendReviewStore.Review review) {
        return new Result(proposal.id(), false, reasons,
                review == null ? null : review.id(),
                review == null ? null : review.reviewerId(),
                proposal.sourceSnapshotId());
    }
}
