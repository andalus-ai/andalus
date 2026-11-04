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

class JdbcFeedbackTrendReviewStoreTest {

    @Test
    void appendsAndRecoversMultipleHumanDecisionsWithReasonCodes() throws Exception {
        JdbcDataSource dataSource = dataSource();
        applyMigrations(dataSource);
        insertSnapshot(dataSource, "baseline", "TASK", "task-1");
        insertSnapshot(dataSource, "current", "TASK", "task-1");
        var assessment = assessment(subject("task-1"), "baseline", "current");

        JdbcFeedbackTrendReviewStore store = new JdbcFeedbackTrendReviewStore(dataSource);
        var first = store.append(assessment, "reviewer-1",
                JdbcFeedbackTrendReviewStore.Disposition.NEEDS_MORE_EVIDENCE, "Collect another independent review");
        var second = store.append(assessment, "reviewer-2",
                JdbcFeedbackTrendReviewStore.Disposition.ACCEPTED, "Evidence is sufficient after manual inspection");

        var recovered = new JdbcFeedbackTrendReviewStore(dataSource).findBySubject(subject("task-1"));
        assertThat(recovered).hasSize(2);
        assertThat(recovered).extracting(JdbcFeedbackTrendReviewStore.Review::id)
                .containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(recovered).allSatisfy(review -> {
            assertThat(review.baselineSnapshotId()).isEqualTo("baseline");
            assertThat(review.currentSnapshotId()).isEqualTo("current");
            assertThat(review.assessmentDecision())
                    .isEqualTo(FeedbackTrendReviewPolicy.Decision.REVIEW_REQUIRED);
            assertThat(review.assessmentReasons())
                    .containsExactly(FeedbackTrendReviewPolicy.Reason.REGRESSION_DETECTED);
        });
    }

    @Test
    void rejectsSnapshotFromAnotherTypedSubject() throws Exception {
        JdbcDataSource dataSource = dataSource();
        applyMigrations(dataSource);
        insertSnapshot(dataSource, "baseline", "TASK", "task-1");
        insertSnapshot(dataSource, "current", "TASK", "task-2");

        assertThatThrownBy(() -> new JdbcFeedbackTrendReviewStore(dataSource).append(
                assessment(subject("task-1"), "baseline", "current"), "reviewer",
                JdbcFeedbackTrendReviewStore.Disposition.REJECTED, "Wrong subject"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresBoundedReviewerAndRationale() throws Exception {
        JdbcDataSource dataSource = dataSource();
        applyMigrations(dataSource);
        insertSnapshot(dataSource, "current", "TASK", "task-1");
        var store = new JdbcFeedbackTrendReviewStore(dataSource);
        var assessment = assessment(subject("task-1"), null, "current");

        assertThatThrownBy(() -> store.append(assessment, " ", 
                JdbcFeedbackTrendReviewStore.Disposition.ACKNOWLEDGED, "rationale"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.append(assessment, "reviewer",
                JdbcFeedbackTrendReviewStore.Disposition.ACKNOWLEDGED, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.append(assessment, "x".repeat(256),
                JdbcFeedbackTrendReviewStore.Disposition.ACKNOWLEDGED, "rationale"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static FeedbackAggregationRecoveryService.Subject subject(String id) {
        return new FeedbackAggregationRecoveryService.Subject(
                FeedbackAggregationRecoveryService.SubjectKind.TASK, id);
    }

    private static FeedbackTrendReviewPolicy.Assessment assessment(
            FeedbackAggregationRecoveryService.Subject subject, String baseline, String current) {
        return new FeedbackTrendReviewPolicy.Assessment(subject,
                FeedbackTrendReviewPolicy.Decision.REVIEW_REQUIRED,
                List.of(FeedbackTrendReviewPolicy.Reason.REGRESSION_DETECTED),
                baseline, current, 0, 0, 0);
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:review_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        source.setUser("sa");
        source.setPassword("");
        return source;
    }

    private static void applyMigrations(DataSource source) throws Exception {
        for (String sql : List.of(
                "CREATE TABLE andalus_feedback_aggregation_snapshot (snapshot_id VARCHAR(36) PRIMARY KEY, subject_kind VARCHAR(32) NOT NULL, subject_id VARCHAR(255) NOT NULL, outcome VARCHAR(64) NOT NULL, positive_weight DOUBLE PRECISION NOT NULL, negative_weight DOUBLE PRECISION NOT NULL, ambiguous_weight DOUBLE PRECISION NOT NULL, positive_ratio DOUBLE PRECISION NOT NULL, negative_ratio DOUBLE PRECISION NOT NULL, distinct_sources INTEGER NOT NULL, evidence_references INTEGER NOT NULL, trust_policy_fingerprint VARCHAR(64) NOT NULL, aggregation_policy_fingerprint VARCHAR(64) NOT NULL, corroboration_fingerprint VARCHAR(64) NOT NULL, created_at_epoch_millis BIGINT NOT NULL)",
                migrationSql())) {
            try (Connection connection = source.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }

    private static String migrationSql() throws Exception {
        try (var input = JdbcFeedbackTrendReviewStoreTest.class.getResourceAsStream(
                "/db/migration/V20261103_04__feedback_trend_review_decisions.sql")) {
            if (input == null) throw new IllegalStateException("review migration resource missing");
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static void insertSnapshot(DataSource source, String id, String kind, String subject)
            throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO andalus_feedback_aggregation_snapshot
                     (snapshot_id, subject_kind, subject_id, outcome, positive_weight, negative_weight,
                      ambiguous_weight, positive_ratio, negative_ratio, distinct_sources,
                      evidence_references, trust_policy_fingerprint, aggregation_policy_fingerprint,
                      corroboration_fingerprint, created_at_epoch_millis)
                     VALUES (?, ?, ?, 'CONSENSUS_POSITIVE', 1, 0, 0, 1, 0, 2, 2, 'trust',
                             'aggregation', 'corroboration', 1)
                     """)) {
            insert.setString(1, id);
            insert.setString(2, kind);
            insert.setString(3, subject);
            insert.executeUpdate();
        }
    }
}
