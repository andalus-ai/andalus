package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedbackAdaptationGateTest {
    private final FeedbackAggregationRecoveryService.Subject subject =
            new FeedbackAggregationRecoveryService.Subject(
                    FeedbackAggregationRecoveryService.SubjectKind.TASK, "task-gate");

    @Test
    void exactApprovedProposalByAuthenticatedReviewerEntersShadowEvaluation() {
        var proposal = proposal("proposal-1", "snapshot-current", "sha256:policy");
        var result = FeedbackAdaptationGate.evaluate(proposal,
                Optional.of(approval(proposal, JdbcFeedbackAdaptationApprovalStore.Decision.APPROVED,
                        JdbcFeedbackTrendReviewStore.Disposition.ACCEPTED,
                        FeedbackTrendReviewPolicy.Decision.REVIEW_REQUIRED, "reviewer-1")), "reviewer-1");
        assertThat(result.eligibleForShadowEvaluation()).isTrue();
        assertThat(result.blockReasons()).isEmpty();
    }

    @Test
    void missingOrRejectedApprovalFailsClosed() {
        var proposal = proposal("proposal-1", "snapshot-current", "sha256:policy");
        assertThat(FeedbackAdaptationGate.evaluate(proposal, Optional.empty(), "reviewer-1")
                .blockReasons()).containsExactly(FeedbackAdaptationGate.BlockReason.APPROVAL_NOT_FOUND);
        var rejected = approval(proposal, JdbcFeedbackAdaptationApprovalStore.Decision.REJECTED,
                JdbcFeedbackTrendReviewStore.Disposition.ACCEPTED,
                FeedbackTrendReviewPolicy.Decision.REVIEW_REQUIRED, "reviewer-1");
        assertThat(FeedbackAdaptationGate.evaluate(proposal, Optional.of(rejected), "reviewer-1")
                .blockReasons()).contains(FeedbackAdaptationGate.BlockReason.APPROVAL_NOT_GRANTED);
    }

    @Test
    void approvalMustMatchExactProposalSnapshotFingerprintAndAuthenticatedReviewer() {
        var proposal = proposal("proposal-1", "snapshot-current", "sha256:policy");
        var approved = approval(proposal, JdbcFeedbackAdaptationApprovalStore.Decision.APPROVED,
                JdbcFeedbackTrendReviewStore.Disposition.ACCEPTED,
                FeedbackTrendReviewPolicy.Decision.REVIEW_REQUIRED, "reviewer-1");

        assertThat(FeedbackAdaptationGate.evaluate(proposal("proposal-2", "snapshot-current", "sha256:policy"),
                Optional.of(approved), "reviewer-1").blockReasons())
                .contains(FeedbackAdaptationGate.BlockReason.PROPOSAL_ID_MISMATCH);
        assertThat(FeedbackAdaptationGate.evaluate(proposal("proposal-1", "snapshot-new", "sha256:policy"),
                Optional.of(approved), "reviewer-1").blockReasons())
                .contains(FeedbackAdaptationGate.BlockReason.SNAPSHOT_MISMATCH);
        assertThat(FeedbackAdaptationGate.evaluate(proposal("proposal-1", "snapshot-current", "sha256:other"),
                Optional.of(approved), "reviewer-1").blockReasons())
                .contains(FeedbackAdaptationGate.BlockReason.POLICY_FINGERPRINT_MISMATCH);
        assertThat(FeedbackAdaptationGate.evaluate(proposal, Optional.of(approved), "reviewer-2")
                .blockReasons()).contains(FeedbackAdaptationGate.BlockReason.REVIEWER_IDENTITY_MISMATCH);
    }

    @Test
    void onlyAcceptedNonInconclusiveTrendReviewCanBeApprovedForShadow() {
        var proposal = proposal("proposal-1", "snapshot-current", "sha256:policy");
        var notAccepted = approval(proposal, JdbcFeedbackAdaptationApprovalStore.Decision.APPROVED,
                JdbcFeedbackTrendReviewStore.Disposition.REJECTED,
                FeedbackTrendReviewPolicy.Decision.REVIEW_REQUIRED, "reviewer-1");
        assertThat(FeedbackAdaptationGate.evaluate(proposal, Optional.of(notAccepted), "reviewer-1")
                .blockReasons()).contains(FeedbackAdaptationGate.BlockReason.REVIEW_NOT_ACCEPTED);

        var inconclusive = approval(proposal, JdbcFeedbackAdaptationApprovalStore.Decision.APPROVED,
                JdbcFeedbackTrendReviewStore.Disposition.ACCEPTED,
                FeedbackTrendReviewPolicy.Decision.INCONCLUSIVE, "reviewer-1");
        assertThat(FeedbackAdaptationGate.evaluate(proposal, Optional.of(inconclusive), "reviewer-1")
                .blockReasons()).contains(FeedbackAdaptationGate.BlockReason.INCONCLUSIVE_ASSESSMENT);
    }

    @Test
    void invalidProposalIsRejected() {
        assertThatThrownBy(() -> new FeedbackAdaptationGate.Proposal(" ", subject, "snapshot", "fingerprint"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private FeedbackAdaptationGate.Proposal proposal(String id, String snapshot, String fingerprint) {
        return new FeedbackAdaptationGate.Proposal(id, subject, snapshot, fingerprint);
    }

    private JdbcFeedbackAdaptationApprovalStore.Approval approval(
            FeedbackAdaptationGate.Proposal proposal, JdbcFeedbackAdaptationApprovalStore.Decision decision,
            JdbcFeedbackTrendReviewStore.Disposition reviewDisposition,
            FeedbackTrendReviewPolicy.Decision assessmentDecision, String reviewer) {
        return new JdbcFeedbackAdaptationApprovalStore.Approval("approval-1", proposal.id(),
                proposal.subject(), proposal.sourceSnapshotId(), "review-1", proposal.policyFingerprint(),
                reviewer, decision, "Explicit proposal disposition", Instant.parse("2026-11-03T00:00:00Z"),
                reviewDisposition, assessmentDecision);
    }
}
