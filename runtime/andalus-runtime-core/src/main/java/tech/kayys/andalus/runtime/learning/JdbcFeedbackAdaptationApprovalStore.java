package tech.kayys.andalus.runtime.learning;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Append-only, proposal-specific human decisions authorizing shadow evaluation. */
public final class JdbcFeedbackAdaptationApprovalStore {
    public enum Decision { APPROVED, REJECTED }

    public record Approval(
            String id,
            String proposalId,
            FeedbackAggregationRecoveryService.Subject subject,
            String sourceSnapshotId,
            String reviewId,
            String policyFingerprint,
            String reviewerId,
            Decision decision,
            String rationale,
            Instant decidedAt,
            JdbcFeedbackTrendReviewStore.Disposition reviewDisposition,
            FeedbackTrendReviewPolicy.Decision assessmentDecision) {
        public Approval {
            Objects.requireNonNull(id);
            Objects.requireNonNull(proposalId);
            Objects.requireNonNull(subject);
            Objects.requireNonNull(sourceSnapshotId);
            Objects.requireNonNull(reviewId);
            Objects.requireNonNull(policyFingerprint);
            Objects.requireNonNull(reviewerId);
            Objects.requireNonNull(decision);
            Objects.requireNonNull(rationale);
            Objects.requireNonNull(decidedAt);
            Objects.requireNonNull(reviewDisposition);
            Objects.requireNonNull(assessmentDecision);
        }
    }

    private final DataSource dataSource;

    public JdbcFeedbackAdaptationApprovalStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    public void verifySchema() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT approval_id FROM andalus_feedback_adaptation_approval WHERE 1 = 0")) {
            query.executeQuery().close();
        } catch (SQLException exception) {
            throw new IllegalStateException("Feedback adaptation approval schema is not ready", exception);
        }
    }

    public Approval append(FeedbackAdaptationGate.Proposal proposal, String reviewId,
                           String reviewerId, Decision decision, String rationale) {
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(decision, "decision");
        String reviewer = requiredText(reviewerId, 255, "reviewerId");
        String reason = requiredText(rationale, 4000, "rationale");
        requiredText(reviewId, 36, "reviewId");
        verifySchema();

        String id = UUID.randomUUID().toString();
        Instant decidedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        ReviewContext review;
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                review = verifyReview(connection, proposal, reviewId);
                if (decision == Decision.APPROVED
                        && (review.disposition() != JdbcFeedbackTrendReviewStore.Disposition.ACCEPTED
                        || review.assessmentDecision() == FeedbackTrendReviewPolicy.Decision.INCONCLUSIVE)) {
                    throw new IllegalArgumentException(
                            "proposal approval requires an accepted, conclusive trend review");
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO andalus_feedback_adaptation_approval
                            (approval_id, proposal_id, subject_kind, subject_id, source_snapshot_id,
                             review_id, policy_fingerprint, reviewer_id, decision, rationale,
                             decided_at_epoch_millis)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, id);
                    insert.setString(2, proposal.id());
                    insert.setString(3, proposal.subject().kind().name());
                    insert.setString(4, proposal.subject().id());
                    insert.setString(5, proposal.sourceSnapshotId());
                    insert.setString(6, reviewId);
                    insert.setString(7, proposal.policyFingerprint());
                    insert.setString(8, reviewer);
                    insert.setString(9, decision.name());
                    insert.setString(10, reason);
                    insert.setLong(11, decidedAt.toEpochMilli());
                    insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            } finally {
                try { connection.setAutoCommit(previousAutoCommit); } catch (SQLException ignored) { }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to append feedback adaptation approval", exception);
        }
        return new Approval(id, proposal.id(), proposal.subject(), proposal.sourceSnapshotId(), reviewId,
                proposal.policyFingerprint(), reviewer, decision, reason, decidedAt,
                review.disposition(), review.assessmentDecision());
    }

    public List<Approval> findByProposal(FeedbackAdaptationGate.Proposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        verifySchema();
        List<Approval> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT a.approval_id, a.proposal_id, a.subject_kind, a.subject_id,
                            a.source_snapshot_id, a.review_id, a.policy_fingerprint, a.reviewer_id,
                            a.decision, a.rationale, a.decided_at_epoch_millis,
                            r.disposition AS review_disposition,
                            r.assessment_decision AS assessment_decision
                     FROM andalus_feedback_adaptation_approval a
                     JOIN andalus_feedback_trend_review r ON r.review_id = a.review_id
                     WHERE a.subject_kind = ? AND a.subject_id = ? AND a.proposal_id = ?
                     ORDER BY a.decided_at_epoch_millis, a.approval_id
                     """)) {
            query.setString(1, proposal.subject().kind().name());
            query.setString(2, proposal.subject().id());
            query.setString(3, proposal.id());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(new Approval(rows.getString("approval_id"), rows.getString("proposal_id"),
                            proposal.subject(), rows.getString("source_snapshot_id"),
                            rows.getString("review_id"), rows.getString("policy_fingerprint"),
                            rows.getString("reviewer_id"), Decision.valueOf(rows.getString("decision")),
                            rows.getString("rationale"), Instant.ofEpochMilli(rows.getLong("decided_at_epoch_millis")),
                            JdbcFeedbackTrendReviewStore.Disposition.valueOf(rows.getString("review_disposition")),
                            FeedbackTrendReviewPolicy.Decision.valueOf(rows.getString("assessment_decision"))));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to recover feedback adaptation approvals", exception);
        }
        return List.copyOf(result);
    }

    private ReviewContext verifyReview(Connection connection, FeedbackAdaptationGate.Proposal proposal,
                                       String reviewId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT subject_kind, subject_id, current_snapshot_id, disposition, assessment_decision
                FROM andalus_feedback_trend_review WHERE review_id = ?
                """)) {
            query.setString(1, reviewId);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()
                        || !proposal.subject().kind().name().equals(row.getString("subject_kind"))
                        || !proposal.subject().id().equals(row.getString("subject_id"))
                        || !proposal.sourceSnapshotId().equals(row.getString("current_snapshot_id"))) {
                    throw new IllegalArgumentException("review does not match proposal subject and source snapshot");
                }
                return new ReviewContext(JdbcFeedbackTrendReviewStore.Disposition.valueOf(
                        row.getString("disposition")), FeedbackTrendReviewPolicy.Decision.valueOf(
                        row.getString("assessment_decision")));
            }
        }
    }

    private static String requiredText(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " must be non-blank and at most " + max + " characters");
        }
        return value;
    }

    private record ReviewContext(JdbcFeedbackTrendReviewStore.Disposition disposition,
                                 FeedbackTrendReviewPolicy.Decision assessmentDecision) { }
}
