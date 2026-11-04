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

/** JDBC append-only persistence for human dispositions of advisory trend reviews. */
public final class JdbcFeedbackTrendReviewStore {
    public enum Disposition {
        ACKNOWLEDGED,
        ACCEPTED,
        REJECTED,
        NEEDS_MORE_EVIDENCE
    }

    public record Review(
            String id,
            FeedbackAggregationRecoveryService.Subject subject,
            String baselineSnapshotId,
            String currentSnapshotId,
            FeedbackTrendReviewPolicy.Decision assessmentDecision,
            List<FeedbackTrendReviewPolicy.Reason> assessmentReasons,
            String reviewerId,
            Disposition disposition,
            String rationale,
            Instant reviewedAt) {
        public Review {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(assessmentDecision, "assessmentDecision");
            assessmentReasons = List.copyOf(assessmentReasons);
            Objects.requireNonNull(reviewerId, "reviewerId");
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(rationale, "rationale");
            Objects.requireNonNull(reviewedAt, "reviewedAt");
        }
    }

    private final DataSource dataSource;

    public JdbcFeedbackTrendReviewStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public void verifySchema() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement header = connection.prepareStatement(
                     "SELECT review_id FROM andalus_feedback_trend_review WHERE 1 = 0");
             PreparedStatement reasons = connection.prepareStatement(
                     "SELECT review_id FROM andalus_feedback_trend_review_reason WHERE 1 = 0")) {
            header.executeQuery().close();
            reasons.executeQuery().close();
        } catch (SQLException exception) {
            throw new IllegalStateException("Feedback trend review schema is not ready", exception);
        }
    }

    /** Appends a reviewer disposition; each call creates a distinct immutable audit record. */
    public Review append(
            FeedbackTrendReviewPolicy.Assessment assessment,
            String reviewerId,
            Disposition disposition,
            String rationale) {
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(disposition, "disposition");
        String reviewer = requiredText(reviewerId, 255, "reviewerId");
        String reason = requiredText(rationale, 4000, "rationale");
        if (assessment.subject().id().length() > 255) {
            throw new IllegalArgumentException("subject identifier exceeds 255 characters");
        }
        if (assessment.currentSnapshotId() == null || assessment.currentSnapshotId().isBlank()) {
            throw new IllegalArgumentException("current snapshot id is required");
        }
        verifySchema();

        String id = UUID.randomUUID().toString();
        Instant reviewedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                verifySnapshotOwnership(connection, assessment);
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO andalus_feedback_trend_review
                            (review_id, subject_kind, subject_id, baseline_snapshot_id,
                             current_snapshot_id, assessment_decision, reviewer_id,
                             disposition, rationale, reviewed_at_epoch_millis)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, id);
                    insert.setString(2, assessment.subject().kind().name());
                    insert.setString(3, assessment.subject().id());
                    insert.setString(4, assessment.baselineSnapshotId());
                    insert.setString(5, assessment.currentSnapshotId());
                    insert.setString(6, assessment.decision().name());
                    insert.setString(7, reviewer);
                    insert.setString(8, disposition.name());
                    insert.setString(9, reason);
                    insert.setLong(10, reviewedAt.toEpochMilli());
                    insert.executeUpdate();
                }
                try (PreparedStatement insertReason = connection.prepareStatement("""
                        INSERT INTO andalus_feedback_trend_review_reason
                            (review_id, reason_order, reason_code) VALUES (?, ?, ?)
                        """)) {
                    int order = 0;
                    for (FeedbackTrendReviewPolicy.Reason code : assessment.reasons()) {
                        insertReason.setString(1, id);
                        insertReason.setInt(2, order++);
                        insertReason.setString(3, code.name());
                        insertReason.addBatch();
                    }
                    insertReason.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // The connection is closed at method exit.
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to append feedback trend review", exception);
        }
        return new Review(id, assessment.subject(), assessment.baselineSnapshotId(),
                assessment.currentSnapshotId(), assessment.decision(), assessment.reasons(),
                reviewer, disposition, reason, reviewedAt);
    }

    /** Returns all immutable review decisions for the exact typed subject, oldest first. */
    public List<Review> findBySubject(FeedbackAggregationRecoveryService.Subject subject) {
        Objects.requireNonNull(subject, "subject");
        verifySchema();
        List<ReviewHeader> headers = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT review_id, baseline_snapshot_id, current_snapshot_id,
                            assessment_decision, reviewer_id, disposition, rationale,
                            reviewed_at_epoch_millis
                     FROM andalus_feedback_trend_review
                     WHERE subject_kind = ? AND subject_id = ?
                     ORDER BY reviewed_at_epoch_millis, review_id
                     """)) {
            query.setString(1, subject.kind().name());
            query.setString(2, subject.id());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    headers.add(new ReviewHeader(rows.getString("review_id"),
                            rows.getString("baseline_snapshot_id"), rows.getString("current_snapshot_id"),
                            rows.getString("assessment_decision"), rows.getString("reviewer_id"),
                            rows.getString("disposition"), rows.getString("rationale"),
                            rows.getLong("reviewed_at_epoch_millis")));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read feedback trend reviews", exception);
        }

        List<Review> reviews = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (ReviewHeader header : headers) {
                List<FeedbackTrendReviewPolicy.Reason> reasons = new ArrayList<>();
                try (PreparedStatement query = connection.prepareStatement("""
                        SELECT reason_code FROM andalus_feedback_trend_review_reason
                        WHERE review_id = ? ORDER BY reason_order
                        """)) {
                    query.setString(1, header.id());
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) {
                            reasons.add(FeedbackTrendReviewPolicy.Reason.valueOf(rows.getString(1)));
                        }
                    }
                }
                reviews.add(new Review(header.id(), subject, header.baselineId(), header.currentId(),
                        FeedbackTrendReviewPolicy.Decision.valueOf(header.assessmentDecision()),
                        reasons, header.reviewer(), Disposition.valueOf(header.disposition()),
                        header.rationale(), Instant.ofEpochMilli(header.reviewedAt())));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read feedback trend review reasons", exception);
        }
        return List.copyOf(reviews);
    }

    private void verifySnapshotOwnership(Connection connection,
            FeedbackTrendReviewPolicy.Assessment assessment) throws SQLException {
        verifySnapshot(connection, assessment.currentSnapshotId(), assessment.subject());
        if (assessment.baselineSnapshotId() != null) {
            verifySnapshot(connection, assessment.baselineSnapshotId(), assessment.subject());
        }
    }

    private void verifySnapshot(Connection connection, String snapshotId,
            FeedbackAggregationRecoveryService.Subject subject) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT subject_kind, subject_id FROM andalus_feedback_aggregation_snapshot
                WHERE snapshot_id = ?
                """)) {
            query.setString(1, snapshotId);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()
                        || !subject.kind().name().equals(row.getString("subject_kind"))
                        || !subject.id().equals(row.getString("subject_id"))) {
                    throw new IllegalArgumentException("review snapshot does not belong to the assessment subject");
                }
            }
        }
    }

    private static String requiredText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be non-blank and at most " + maxLength + " characters");
        }
        return value;
    }

    private record ReviewHeader(String id, String baselineId, String currentId,
            String assessmentDecision, String reviewer, String disposition,
            String rationale, long reviewedAt) {
    }
}
