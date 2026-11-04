package tech.kayys.andalus.runtime.learning;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcFeedbackAdaptationApprovalStoreTest {

    @Test
    void persistsAndRecoversProposalSpecificApproval() throws Exception {
        JdbcDataSource source = dataSource();
        schema(source);
        insertReview(source, "review-1", "task-1", "snapshot-1", "ACCEPTED", "REVIEW_REQUIRED");
        var proposal = proposal();
        var store = new JdbcFeedbackAdaptationApprovalStore(source);

        var saved = store.append(proposal, "review-1", "reviewer-1",
                JdbcFeedbackAdaptationApprovalStore.Decision.APPROVED, "Approve exact policy candidate");
        var recovered = new JdbcFeedbackAdaptationApprovalStore(source).findByProposal(proposal);

        assertThat(recovered).hasSize(1);
        assertThat(recovered.get(0).id()).isEqualTo(saved.id());
        assertThat(recovered.get(0).policyFingerprint()).isEqualTo("sha256:policy");
        assertThat(recovered.get(0).reviewDisposition())
                .isEqualTo(JdbcFeedbackTrendReviewStore.Disposition.ACCEPTED);
        assertThat(FeedbackAdaptationGate.evaluate(proposal,
                java.util.Optional.of(recovered.get(0)), "reviewer-1").eligibleForShadowEvaluation()).isTrue();
    }

    @Test
    void rejectsApprovalWhenReviewIsNotAcceptedOrIsInconclusive() throws Exception {
        JdbcDataSource source = dataSource();
        schema(source);
        insertReview(source, "review-rejected", "task-1", "snapshot-1", "REJECTED", "REVIEW_REQUIRED");
        insertReview(source, "review-inconclusive", "task-1", "snapshot-1", "ACCEPTED", "INCONCLUSIVE");
        var store = new JdbcFeedbackAdaptationApprovalStore(source);

        assertThatThrownBy(() -> store.append(proposal(), "review-rejected", "reviewer-1",
                JdbcFeedbackAdaptationApprovalStore.Decision.APPROVED, "Not acceptable"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.append(proposal(), "review-inconclusive", "reviewer-1",
                JdbcFeedbackAdaptationApprovalStore.Decision.APPROVED, "Insufficient evidence"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReviewThatDoesNotMatchProposalSubjectOrSnapshot() throws Exception {
        JdbcDataSource source = dataSource();
        schema(source);
        insertReview(source, "review-1", "other-task", "snapshot-1", "ACCEPTED", "REVIEW_REQUIRED");

        assertThatThrownBy(() -> new JdbcFeedbackAdaptationApprovalStore(source).append(
                proposal(), "review-1", "reviewer-1",
                JdbcFeedbackAdaptationApprovalStore.Decision.APPROVED, "Mismatch"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAuthenticatedReviewerAndBoundedRationale() throws Exception {
        JdbcDataSource source = dataSource();
        schema(source);
        insertReview(source, "review-1", "task-1", "snapshot-1", "ACCEPTED", "REVIEW_REQUIRED");
        var store = new JdbcFeedbackAdaptationApprovalStore(source);

        assertThatThrownBy(() -> store.append(proposal(), "review-1", " ",
                JdbcFeedbackAdaptationApprovalStore.Decision.APPROVED, "rationale"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.append(proposal(), "review-1", "reviewer-1",
                JdbcFeedbackAdaptationApprovalStore.Decision.APPROVED, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static FeedbackAdaptationGate.Proposal proposal() {
        return new FeedbackAdaptationGate.Proposal("proposal-1",
                new FeedbackAggregationRecoveryService.Subject(
                        FeedbackAggregationRecoveryService.SubjectKind.TASK, "task-1"),
                "snapshot-1", "sha256:policy");
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:approval_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        source.setUser("sa");
        source.setPassword("");
        return source;
    }

    private static void schema(DataSource source) throws Exception {
        List<String> statements = List.of(
                "CREATE TABLE andalus_feedback_aggregation_snapshot (snapshot_id VARCHAR(36) PRIMARY KEY, subject_kind VARCHAR(32) NOT NULL, subject_id VARCHAR(255) NOT NULL)",
                "CREATE TABLE andalus_feedback_trend_review (review_id VARCHAR(36) PRIMARY KEY, subject_kind VARCHAR(32) NOT NULL, subject_id VARCHAR(255) NOT NULL, current_snapshot_id VARCHAR(36) NOT NULL, disposition VARCHAR(32) NOT NULL, assessment_decision VARCHAR(32) NOT NULL)",
                "CREATE TABLE andalus_feedback_adaptation_approval (approval_id VARCHAR(36) PRIMARY KEY, proposal_id VARCHAR(255) NOT NULL, subject_kind VARCHAR(32) NOT NULL, subject_id VARCHAR(255) NOT NULL, source_snapshot_id VARCHAR(36) NOT NULL, review_id VARCHAR(36) NOT NULL, policy_fingerprint VARCHAR(128) NOT NULL, reviewer_id VARCHAR(255) NOT NULL, decision VARCHAR(16) NOT NULL, rationale VARCHAR(4000) NOT NULL, decided_at_epoch_millis BIGINT NOT NULL, FOREIGN KEY (source_snapshot_id) REFERENCES andalus_feedback_aggregation_snapshot(snapshot_id), FOREIGN KEY (review_id) REFERENCES andalus_feedback_trend_review(review_id))"
        );
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
    }

    private static void insertReview(DataSource source, String reviewId, String subjectId,
                                     String snapshotId, String disposition, String assessment) throws Exception {
        try (Connection connection = source.getConnection()) {
            try (PreparedStatement snapshot = connection.prepareStatement(
                    "INSERT INTO andalus_feedback_aggregation_snapshot VALUES (?, 'TASK', ?)")) {
                snapshot.setString(1, snapshotId);
                snapshot.setString(2, subjectId);
                snapshot.executeUpdate();
            }
            try (PreparedStatement review = connection.prepareStatement(
                    "INSERT INTO andalus_feedback_trend_review VALUES (?, 'TASK', ?, ?, ?, ?)")) {
                review.setString(1, reviewId);
                review.setString(2, subjectId);
                review.setString(3, snapshotId);
                review.setString(4, disposition);
                review.setString(5, assessment);
                review.executeUpdate();
            }
        }
    }
}
