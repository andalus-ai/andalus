package tech.kayys.andalus.runtime.learning;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Fail-closed gate from an explicit, persisted proposal decision to shadow evaluation.
 * A generic feedback-trend review disposition is not approval of a learning-policy proposal.
 */
public final class FeedbackAdaptationGate {

    public enum BlockReason {
        APPROVAL_NOT_FOUND,
        APPROVAL_NOT_GRANTED,
        REVIEW_NOT_ACCEPTED,
        INCONCLUSIVE_ASSESSMENT,
        SUBJECT_MISMATCH,
        SNAPSHOT_MISMATCH,
        PROPOSAL_ID_MISMATCH,
        POLICY_FINGERPRINT_MISMATCH,
        REVIEWER_IDENTITY_MISMATCH
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
            String approvalId,
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

    /** authenticatedReviewerId must be derived from the caller's trusted security context. */
    public static Result evaluate(
            Proposal proposal,
            Optional<JdbcFeedbackAdaptationApprovalStore.Approval> storedApproval,
            String authenticatedReviewerId) {
        Objects.requireNonNull(proposal, "proposal");
        if (storedApproval == null || storedApproval.isEmpty()) {
            return blocked(proposal, List.of(BlockReason.APPROVAL_NOT_FOUND), null);
        }

        JdbcFeedbackAdaptationApprovalStore.Approval approval = storedApproval.get();
        List<BlockReason> reasons = new ArrayList<>();
        if (!approval.subject().equals(proposal.subject())) reasons.add(BlockReason.SUBJECT_MISMATCH);
        if (!approval.sourceSnapshotId().equals(proposal.sourceSnapshotId())) {
            reasons.add(BlockReason.SNAPSHOT_MISMATCH);
        }
        if (!approval.proposalId().equals(proposal.id())) reasons.add(BlockReason.PROPOSAL_ID_MISMATCH);
        if (!approval.policyFingerprint().equals(proposal.policyFingerprint())) {
            reasons.add(BlockReason.POLICY_FINGERPRINT_MISMATCH);
        }
        if (approval.decision() != JdbcFeedbackAdaptationApprovalStore.Decision.APPROVED) {
            reasons.add(BlockReason.APPROVAL_NOT_GRANTED);
        }
        if (approval.reviewDisposition() != JdbcFeedbackTrendReviewStore.Disposition.ACCEPTED) {
            reasons.add(BlockReason.REVIEW_NOT_ACCEPTED);
        }
        if (approval.assessmentDecision() == FeedbackTrendReviewPolicy.Decision.INCONCLUSIVE) {
            reasons.add(BlockReason.INCONCLUSIVE_ASSESSMENT);
        }
        if (authenticatedReviewerId == null || authenticatedReviewerId.isBlank()
                || !approval.reviewerId().equals(authenticatedReviewerId)) {
            reasons.add(BlockReason.REVIEWER_IDENTITY_MISMATCH);
        }
        if (reasons.isEmpty()) {
            return new Result(proposal.id(), true, List.of(), approval.id(),
                    approval.reviewerId(), proposal.sourceSnapshotId());
        }
        return blocked(proposal, reasons, approval);
    }

    private static Result blocked(Proposal proposal, List<BlockReason> reasons,
                                  JdbcFeedbackAdaptationApprovalStore.Approval approval) {
        return new Result(proposal.id(), false, reasons,
                approval == null ? null : approval.id(),
                approval == null ? null : approval.reviewerId(),
                proposal.sourceSnapshotId());
    }
}
