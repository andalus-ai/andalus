package tech.kayys.andalus.runtime.learning;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcShadowEvaluationStoreTest {

    @Test
    void persistsAndRecoversOrderedMetricsAndEvidenceAcrossStoreInstances() throws Exception {
        JdbcDataSource source = dataSource();
        schema(source);
        insertApproval(source, "approval-1", "proposal-1", "task-1", "snapshot-1", "fp-1");
        ShadowEvaluationReport report = report();

        new JdbcShadowEvaluationStore(source).append(report);
        ShadowEvaluationReport recovered = new JdbcShadowEvaluationStore(source)
                .findByRunId(report.runId()).orElseThrow();

        assertThat(recovered).isEqualTo(report);
        assertThat(new JdbcShadowEvaluationStore(source).findByProposal(proposal())).containsExactly(report);
    }

    @Test
    void exactRunRetryIsIdempotentButChangedPayloadIsRejected() throws Exception {
        JdbcDataSource source = dataSource();
        schema(source);
        insertApproval(source, "approval-1", "proposal-1", "task-1", "snapshot-1", "fp-1");
        var store = new JdbcShadowEvaluationStore(source);
        ShadowEvaluationReport report = report();

        store.append(report);
        assertThat(store.append(report)).isEqualTo(report);
        ShadowEvaluationReport changed = new ShadowEvaluationReport(report.runId(), report.proposalId(),
                report.subject(), report.sourceSnapshotId(), report.approvalId(), report.policyFingerprint(),
                report.baselineEvaluatorId(), report.baselineEvaluatorVersion(),
                report.candidateEvaluatorId(), report.candidateEvaluatorVersion(),
                report.baselineCandidateId(), report.baselineCandidateVersion(),
                report.candidateId(), report.candidateVersion(),
                report.datasetId(), report.datasetVersion(), "different-dataset-fingerprint",
                report.status(), report.metrics(), report.evidenceReferences(), report.evaluatedAt());
        assertThatThrownBy(() -> store.append(changed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different shadow evaluation evidence");
    }

    @Test
    void rejectsReportNotBoundToApprovedProposal() throws Exception {
        JdbcDataSource source = dataSource();
        schema(source);
        insertApproval(source, "approval-1", "different-proposal", "task-1", "snapshot-1", "fp-1");

        assertThatThrownBy(() -> new JdbcShadowEvaluationStore(source).append(report()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
        assertThat(new JdbcShadowEvaluationStore(source).findByRunId("run-1")).isEmpty();
    }

    @Test
    void derivesPassFailAndInconclusiveFromTypedMetricThresholds() {
        var pass = new ShadowEvaluationReport.Metric("accuracy", 0.80, 0.84,
                ShadowEvaluationReport.Direction.HIGHER_IS_BETTER, 0.03);
        var fail = new ShadowEvaluationReport.Metric("latency", 100, 110,
                ShadowEvaluationReport.Direction.LOWER_IS_BETTER, 0);
        assertThat(pass.passes()).isTrue();
        assertThat(fail.passes()).isFalse();
        assertThatThrownBy(() -> new ShadowEvaluationReport("run-1", "proposal-1", subject(),
                "snapshot-1", "approval-1", "fp-1", "baseline", "1", "candidate", "2",
                "baseline-model", "v1", "candidate-model", "v2",
                "dataset", "v1", "dataset-fp", ShadowEvaluationReport.Status.PASS,
                List.of(fail), List.of(), Instant.parse("2026-11-03T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status does not match");
    }

    private static ShadowEvaluationReport report() {
        return new ShadowEvaluationReport("run-1", "proposal-1", subject(), "snapshot-1",
                "approval-1", "fp-1", "baseline-evaluator", "1.0", "candidate-evaluator", "2.0",
                "baseline-model", "v1", "candidate-model", "v2",
                "offline-validation", "2026-11", "dataset-fingerprint",
                ShadowEvaluationReport.Status.PASS,
                List.of(new ShadowEvaluationReport.Metric("accuracy", 0.80, 0.84,
                        ShadowEvaluationReport.Direction.HIGHER_IS_BETTER, 0.03),
                        new ShadowEvaluationReport.Metric("latency", 100, 95,
                                ShadowEvaluationReport.Direction.LOWER_IS_BETTER, 2)),
                List.of("artifact:run-1:metrics", "artifact:run-1:trace"),
                Instant.parse("2026-11-03T04:00:00Z"));
    }

    private static FeedbackAdaptationGate.Proposal proposal() {
        return new FeedbackAdaptationGate.Proposal("proposal-1", subject(), "snapshot-1", "fp-1");
    }

    private static FeedbackAggregationRecoveryService.Subject subject() {
        return new FeedbackAggregationRecoveryService.Subject(
                FeedbackAggregationRecoveryService.SubjectKind.TASK, "task-1");
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:shadow_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        source.setUser("sa");
        source.setPassword("");
        return source;
    }

    private static void schema(DataSource source) throws Exception {
        List<String> ddl = List.of(
                "CREATE TABLE andalus_feedback_aggregation_snapshot (snapshot_id VARCHAR(36) PRIMARY KEY, subject_kind VARCHAR(32) NOT NULL, subject_id VARCHAR(255) NOT NULL)",
                "CREATE TABLE andalus_feedback_trend_review (review_id VARCHAR(36) PRIMARY KEY, disposition VARCHAR(32) NOT NULL, assessment_decision VARCHAR(32) NOT NULL)",
                "CREATE TABLE andalus_feedback_adaptation_approval (approval_id VARCHAR(36) PRIMARY KEY, proposal_id VARCHAR(255) NOT NULL, subject_kind VARCHAR(32) NOT NULL, subject_id VARCHAR(255) NOT NULL, source_snapshot_id VARCHAR(36) NOT NULL, review_id VARCHAR(36) NOT NULL, policy_fingerprint VARCHAR(128) NOT NULL, decision VARCHAR(16) NOT NULL, FOREIGN KEY (source_snapshot_id) REFERENCES andalus_feedback_aggregation_snapshot(snapshot_id), FOREIGN KEY (review_id) REFERENCES andalus_feedback_trend_review(review_id))",
                "CREATE TABLE andalus_feedback_shadow_evaluation (run_id VARCHAR(36) PRIMARY KEY, proposal_id VARCHAR(255) NOT NULL, subject_kind VARCHAR(32) NOT NULL, subject_id VARCHAR(255) NOT NULL, source_snapshot_id VARCHAR(36) NOT NULL, approval_id VARCHAR(36) NOT NULL, policy_fingerprint VARCHAR(128) NOT NULL, baseline_evaluator_id VARCHAR(255) NOT NULL, baseline_evaluator_version VARCHAR(128) NOT NULL, candidate_evaluator_id VARCHAR(255) NOT NULL, candidate_evaluator_version VARCHAR(128) NOT NULL, baseline_candidate_id VARCHAR(255) NOT NULL, baseline_candidate_version VARCHAR(128) NOT NULL, candidate_id VARCHAR(255) NOT NULL, candidate_version VARCHAR(128) NOT NULL, dataset_id VARCHAR(255) NOT NULL, dataset_version VARCHAR(128) NOT NULL, dataset_fingerprint VARCHAR(128) NOT NULL, evaluation_status VARCHAR(16) NOT NULL, evaluated_at_epoch_millis BIGINT NOT NULL, FOREIGN KEY (source_snapshot_id) REFERENCES andalus_feedback_aggregation_snapshot(snapshot_id), FOREIGN KEY (approval_id) REFERENCES andalus_feedback_adaptation_approval(approval_id))",
                "CREATE TABLE andalus_feedback_shadow_evaluation_metric (run_id VARCHAR(36) NOT NULL, metric_order INTEGER NOT NULL, metric_name VARCHAR(128) NOT NULL, baseline_value DOUBLE PRECISION NOT NULL, candidate_value DOUBLE PRECISION NOT NULL, direction VARCHAR(32) NOT NULL, required_improvement DOUBLE PRECISION NOT NULL, PRIMARY KEY (run_id, metric_order), UNIQUE (run_id, metric_name), FOREIGN KEY (run_id) REFERENCES andalus_feedback_shadow_evaluation(run_id))",
                "CREATE TABLE andalus_feedback_shadow_evaluation_evidence (run_id VARCHAR(36) NOT NULL, evidence_order INTEGER NOT NULL, evidence_reference VARCHAR(2048) NOT NULL, PRIMARY KEY (run_id, evidence_order), UNIQUE (run_id, evidence_reference), FOREIGN KEY (run_id) REFERENCES andalus_feedback_shadow_evaluation(run_id))"
        );
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : ddl) statement.execute(sql);
        }
    }

    private static void insertApproval(DataSource source, String approvalId, String proposalId,
                                       String subjectId, String snapshotId, String fingerprint) throws Exception {
        try (Connection connection = source.getConnection()) {
            try (PreparedStatement snapshot = connection.prepareStatement(
                    "INSERT INTO andalus_feedback_aggregation_snapshot VALUES (?, 'TASK', ?)")) {
                snapshot.setString(1, snapshotId);
                snapshot.setString(2, subjectId);
                snapshot.executeUpdate();
            }
            try (PreparedStatement review = connection.prepareStatement(
                    "INSERT INTO andalus_feedback_trend_review VALUES (?, 'ACCEPTED', 'REVIEW_REQUIRED')")) {
                review.setString(1, "review-1");
                review.executeUpdate();
            }
            try (PreparedStatement approval = connection.prepareStatement("""
                    INSERT INTO andalus_feedback_adaptation_approval
                        (approval_id, proposal_id, subject_kind, subject_id, source_snapshot_id,
                         review_id, policy_fingerprint, decision)
                    VALUES (?, ?, 'TASK', ?, ?, 'review-1', ?, 'APPROVED')
                    """)) {
                approval.setString(1, approvalId);
                approval.setString(2, proposalId);
                approval.setString(3, subjectId);
                approval.setString(4, snapshotId);
                approval.setString(5, fingerprint);
                approval.executeUpdate();
            }
        }
    }
}
