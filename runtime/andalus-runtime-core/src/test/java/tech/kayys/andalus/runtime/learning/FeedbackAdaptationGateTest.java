package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedbackAdaptationGateTest {

    private final FeedbackAggregationRecoveryService.Subject subject =
            new FeedbackAggregationRecoveryService.Subject(
                    FeedbackAggregationRecoveryService.SubjectKind.TASK, "task-gate");

    @Test
    void acceptedPersistedReviewByAuthenticatedReviewerAllowsOnlyShadowEvaluation() {
        var proposal = proposal("snapshot-current");
        var review = review("snapshot-current",
                JdbcFeedbackTrendReviewStore.Disposition.ACCEPTED,
                FeedbackTrendReviewPolicy.Decision.REVIEW_REQUIRED, "reviewer-1");

        var result = FeedbackAdaptationGate.evaluate(proposal, Optional.of(review), "reviewer-1");

        assertThat(result.eligibleForShadowEvaluation()).isTrue();
        assertThat(result.blockReasons()).isEmpty();
        assertThat(result.reviewId()).isEqualTo("review-1");
    }

    @Test
    void missingReviewAndUnauthenticatedOrDifferentReviewerFailClosed() {
        var proposal = proposal("snapshot-current");
        assertThat(FeedbackAdaptationGate.evaluate(proposal, Optional.empty(), "reviewer-1")
                .blockReasons()).containsExactly(FeedbackAdaptationGate.BlockReason.REVIEW_NOT_FOUND);

        var review = review("snapshot-current",
                JdbcFeedbackTrendReviewStore.Disposition.ACCEPTED,
                FeedbackTrendReviewPolicy.Decision.REVIEW_REQUIRED, "reviewer-1");
        assertThat(FeedbackAdaptationGate.evaluate(proposal, Optional.of(review), "reviewer-2")
                .blockReasons()).containsExactly(FeedbackAdaptationGate.BlockReason.REVIEWER_IDENTITY_MISMATCH);
        assertThat(FeedbackAdaptationGate.evaluate(proposal, Optional.of(review), null)
                .eligibleForShadowEvaluation()).isFalse();
    }

    @Test
    void rejectedOrInconclusiveReviewCannotOpenGate() {
        var proposal = proposal("snapshot-current");
        var rejected = review("snapshot-current",
                JdbcFeedbackTrendReviewStore.Disposition.REJECTED,
                FeedbackTrendReviewPolicy.Decision.REVIEW_REQUIRED, "reviewer-1");
        assertThat(FeedbackAdaptationGate.evaluate(proposal, Optional.of(rejected), "reviewer-1")
                .blockReasons()).contains(FeedbackAdaptationGate.BlockReason.REVIEW_NOT_ACCEPTED);

        var inconclusive = review("snapshot-current",
                JdbcFeedbackTrendReviewStore.Disposition.ACCEPTED,
                FeedbackTrendReviewPolicy.Decision.INCONCLUSIVE, "reviewer-1");
        assertThat(FeedbackAdaptationGate.evaluate(proposal, Optional.of(inconclusive), "reviewer-1")
                .blockReasons()).contains(FeedbackAdaptationGate.BlockReason.INCONCLUSIVE_ASSESSMENT);
    }

    @Test
    void proposalMustMatchReviewedSubjectAndSnapshot() {
        var proposal = proposal("newer-snapshot");
        var review = review("snapshot-current",
                JdbcFeedbackTrendReviewStore.Disposition.ACCEPTED,
                FeedbackTrendReviewPolicy.Decision.REVIEW_REQUIRED, "reviewer-1");

        var result = FeedbackAdaptationGate.evaluate(proposal, Optional.of(review), "reviewer-1");

        assertThat(result.eligibleForShadowEvaluation()).isFalse();
        assertThat(result.blockReasons()).contains(FeedbackAdaptationGate.BlockReason.SNAPSHOT_MISMATCH);
        var otherSubject = new FeedbackAdaptationGate.Proposal("proposal-1",
                new FeedbackAggregationRecoveryService.Subject(
                        FeedbackAggregationRecoveryService.SubjectKind.GOAL, "task-gate"),
                "snapshot-current", "sha256:policy");
        assertThat(FeedbackAdaptationGate.evaluate(otherSubject, Optional.of(review), "reviewer-1")
                .blockReasons()).contains(FeedbackAdaptationGate.BlockReason.SUBJECT_MISMATCH);
    }

    @Test
    void invalidProposalFieldsAreRejected() {
        assertThatThrownBy(() -> new FeedbackAdaptationGate.Proposal(
                " ", subject, "snapshot", "fingerprint"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private FeedbackAdaptationGate.Proposal proposal(String snapshot) {
        return new FeedbackAdaptationGate.Proposal(
                "proposal-1", subject, snapshot, "sha256:policy");
    }

    private JdbcFeedbackTrendReviewStore.Review review(
            String snapshot, JdbcFeedbackTrendReviewStore.Disposition disposition,
            FeedbackTrendReviewPolicy.Decision decision, String reviewer) {
        return new JdbcFeedbackTrendReviewStore.Review("review-1", subject,
                "snapshot-baseline", snapshot, decision,
                List.of(FeedbackTrendReviewPolicy.Reason.REGRESSION_DETECTED),
                reviewer, disposition, "Reviewed rationale", Instant.parse("2026-11-03T00:00:00Z"));
    }
}
