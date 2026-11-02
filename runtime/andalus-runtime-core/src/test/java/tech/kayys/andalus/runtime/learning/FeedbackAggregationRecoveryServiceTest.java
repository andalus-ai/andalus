package tech.kayys.andalus.runtime.learning;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.harness.learning.FeedbackAggregation;
import tech.kayys.andalus.harness.learning.FeedbackTrustPolicy;
import tech.kayys.andalus.harness.learning.LearningSignal;
import tech.kayys.andalus.harness.learning.LearningSignalPolarity;
import tech.kayys.andalus.harness.learning.LearningSignalType;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackAggregationRecoveryServiceTest {

    private final FeedbackAdmissionService admission = new FeedbackAdmissionService();
    private final FeedbackAggregationRecoveryService recovery = new FeedbackAggregationRecoveryService();

    @Test
    void aggregatesOnlySignalsForExplicitSubjectAndReturnsDeterministicProvenance() throws Exception {
        JdbcDataSource dataSource = dataSource();
        applyMigrations(dataSource);
        FeedbackTrustPolicy.Policy trust = trustPolicy();

        admit(dataSource, signal("signal-b", "reviewer-b", "task-1", "evidence-b",
                LearningSignalPolarity.POSITIVE, "2026-11-02T00:02:00Z"), trust);
        admit(dataSource, signal("signal-a", "reviewer-a", "task-1", "evidence-a",
                LearningSignalPolarity.POSITIVE, "2026-11-02T00:01:00Z"), trust);
        admit(dataSource, signal("signal-other", "reviewer-a", "task-2", "evidence-other",
                LearningSignalPolarity.NEGATIVE, "2026-11-02T00:03:00Z"), trust);

        var report = recovery.aggregate(dataSource,
                new FeedbackAggregationRecoveryService.Subject(
                        FeedbackAggregationRecoveryService.SubjectKind.TASK, "task-1"),
                new FeedbackAggregation.Policy(2, 2, 0.75), trust, Map.of());

        assertThat(report.aggregation().outcome())
                .isEqualTo(FeedbackAggregation.Outcome.CONSENSUS_POSITIVE);
        assertThat(report.aggregation().subject()).isEqualTo("TASK:task-1");
        assertThat(report.signalIds()).containsExactly("signal-a", "signal-b");
        assertThat(report.sourceIds()).containsExactly("reviewer-a", "reviewer-b");
        assertThat(report.evidenceReferences()).containsExactly("evidence-a", "evidence-b");
        assertThat(report.trustPolicyFingerprint()).hasSize(64);
    }

    @Test
    void returnsInsufficientEvidenceWhenTrustPolicyNoLongerAcceptsPersistedSignal() throws Exception {
        JdbcDataSource dataSource = dataSource();
        applyMigrations(dataSource);
        admit(dataSource, signal("signal-a", "reviewer-a", "task-1", "evidence-a",
                LearningSignalPolarity.POSITIVE, "2026-11-02T00:01:00Z"), trustPolicy());

        FeedbackTrustPolicy.Policy changedPolicy = new FeedbackTrustPolicy.Policy(
                Map.of("reviewer-a", 0.2), 0.8, 1, 1);
        var report = recovery.aggregate(dataSource,
                new FeedbackAggregationRecoveryService.Subject(
                        FeedbackAggregationRecoveryService.SubjectKind.TASK, "task-1"),
                new FeedbackAggregation.Policy(1, 1, 0.75), changedPolicy, Map.of());

        assertThat(report.aggregation().outcome())
                .isEqualTo(FeedbackAggregation.Outcome.INSUFFICIENT_EVIDENCE);
        assertThat(report.signalIds()).isEmpty();
        assertThat(report.trustPolicyFingerprint()).isNotEqualTo(
                FeedbackAggregationRecoveryService.fingerprint(trustPolicy()));
    }

    @Test
    void snapshotsAppendImmutableHistoryAndRecoverAllProvenance() throws Exception {
        JdbcDataSource dataSource = dataSource();
        applyMigrations(dataSource);
        FeedbackTrustPolicy.Policy trust = trustPolicy();
        admit(dataSource, signal("signal-a", "reviewer-a", "task-snapshot", "evidence-a",
                LearningSignalPolarity.POSITIVE, "2026-11-02T00:01:00Z"), trust);
        admit(dataSource, signal("signal-b", "reviewer-b", "task-snapshot", "evidence-b",
                LearningSignalPolarity.POSITIVE, "2026-11-02T00:02:00Z"), trust);

        var subject = new FeedbackAggregationRecoveryService.Subject(
                FeedbackAggregationRecoveryService.SubjectKind.TASK, "task-snapshot");
        var policy = new FeedbackAggregation.Policy(2, 2, 0.75);
        var first = recovery.aggregateAndSnapshot(dataSource, subject, policy, trust, Map.of());
        var second = recovery.aggregateAndSnapshot(dataSource, subject, policy, trust, Map.of());

        JdbcFeedbackAggregationSnapshotStore recreated =
                new JdbcFeedbackAggregationSnapshotStore(dataSource);
        recreated.verifySchema();
        var history = recreated.findBySubject(subject);

        assertThat(history).hasSize(2);
        assertThat(history).extracting(JdbcFeedbackAggregationSnapshotStore.Snapshot::id)
                .containsExactly(first.id(), second.id());
        assertThat(history.get(0).aggregation().outcome())
                .isEqualTo(FeedbackAggregation.Outcome.CONSENSUS_POSITIVE);
        assertThat(history.get(0).signalIds()).containsExactly("signal-a", "signal-b");
        assertThat(history.get(0).consideredSignalIds()).containsExactly("signal-a", "signal-b");
        assertThat(history.get(0).sourceIds()).containsExactly("reviewer-a", "reviewer-b");
        assertThat(history.get(0).evidenceReferences()).containsExactly("evidence-a", "evidence-b");
        assertThat(history.get(0).trustPolicyFingerprint()).hasSize(64);
        assertThat(history.get(0).aggregationPolicyFingerprint()).hasSize(64);
        assertThat(history.get(0).corroborationFingerprint()).hasSize(64);
        assertThat(history.get(0).aggregation().reasons()).isEmpty();
    }

    @Test
    void snapshotHistoryIsIsolatedByTypedSubject() throws Exception {
        JdbcDataSource dataSource = dataSource();
        applyMigrations(dataSource);
        FeedbackTrustPolicy.Policy trust = trustPolicy();
        LearningSignal signal = new LearningSignal("signal-a", LearningSignalType.HUMAN_FEEDBACK,
                "reviewer-a", "execution-a", Optional.empty(), Optional.of("same"),
                Optional.of("same"), LearningSignalPolarity.POSITIVE, List.of("evidence-a"),
                Optional.of("same"), Instant.parse("2026-11-02T00:01:00Z"), Map.of());
        admit(dataSource, signal, trust);
        var task = new FeedbackAggregationRecoveryService.Subject(
                FeedbackAggregationRecoveryService.SubjectKind.TASK, "same");
        var goal = new FeedbackAggregationRecoveryService.Subject(
                FeedbackAggregationRecoveryService.SubjectKind.GOAL, "same");
        recovery.aggregateAndSnapshot(dataSource, task,
                new FeedbackAggregation.Policy(1, 1, 0.75), trust, Map.of());
        recovery.aggregateAndSnapshot(dataSource, goal,
                new FeedbackAggregation.Policy(1, 1, 0.75), trust, Map.of());

        JdbcFeedbackAggregationSnapshotStore store = new JdbcFeedbackAggregationSnapshotStore(dataSource);
        assertThat(store.findBySubject(task)).hasSize(1);
        assertThat(store.findBySubject(goal)).hasSize(1);
        assertThat(store.findBySubject(new FeedbackAggregationRecoveryService.Subject(
                FeedbackAggregationRecoveryService.SubjectKind.EVALUATION, "same"))).isEmpty();
    }

    @Test
    void subjectKindsDoNotCollideWhenIdentifiersAreEqual() throws Exception {
        JdbcDataSource dataSource = dataSource();
        applyMigrations(dataSource);
        LearningSignal signal = new LearningSignal("signal-a", LearningSignalType.HUMAN_FEEDBACK,
                "reviewer-a", "execution-a", Optional.of("turn-a"), Optional.of("same-id"),
                Optional.of("same-id"), LearningSignalPolarity.POSITIVE, List.of("evidence-a"),
                Optional.of("same-id"), Instant.parse("2026-11-02T00:01:00Z"), Map.of());
        admit(dataSource, signal, trustPolicy());

        var taskReport = recovery.aggregate(dataSource,
                new FeedbackAggregationRecoveryService.Subject(
                        FeedbackAggregationRecoveryService.SubjectKind.TASK, "same-id"),
                new FeedbackAggregation.Policy(1, 1, 0.75), trustPolicy(), Map.of());
        var goalReport = recovery.aggregate(dataSource,
                new FeedbackAggregationRecoveryService.Subject(
                        FeedbackAggregationRecoveryService.SubjectKind.GOAL, "same-id"),
                new FeedbackAggregation.Policy(1, 1, 0.75), trustPolicy(), Map.of());

        assertThat(taskReport.aggregation().subject()).isEqualTo("TASK:same-id");
        assertThat(goalReport.aggregation().subject()).isEqualTo("GOAL:same-id");
        assertThat(taskReport.signalIds()).containsExactly("signal-a");
        assertThat(goalReport.signalIds()).containsExactly("signal-a");
    }

    private void admit(DataSource dataSource, LearningSignal signal,
                       FeedbackTrustPolicy.Policy trustPolicy) {
        assertThat(admission.admit(dataSource, signal, trustPolicy, Set.of()).accepted()).isTrue();
    }

    private LearningSignal signal(String id, String source, String task, String evidence,
                                  LearningSignalPolarity polarity, String occurredAt) {
        return new LearningSignal(id, LearningSignalType.HUMAN_FEEDBACK, source, "execution-" + id,
                Optional.empty(), Optional.empty(), Optional.of(task), polarity, List.of(evidence),
                Optional.empty(), Instant.parse(occurredAt), Map.of());
    }

    private FeedbackTrustPolicy.Policy trustPolicy() {
        return new FeedbackTrustPolicy.Policy(Map.of("reviewer-a", 1.0, "reviewer-b", 1.0),
                0.8, 1, 1);
    }

    private JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:aggregation_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void applyMigrations(DataSource dataSource) throws Exception {
        for (String resource : List.of(
                "/db/migration/V20261102_01__feedback_evidence_reservations.sql",
                "/db/migration/V20261102_02__admitted_feedback_ledger.sql",
                "/db/migration/V20261102_03__feedback_aggregation_snapshots.sql")) {
            try (InputStream input = getClass().getResourceAsStream(resource)) {
                assertThat(input).isNotNull();
                String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                try (Connection connection = dataSource.getConnection();
                     Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
        }
    }
}
