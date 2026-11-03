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

/** Durable append-only shadow-evaluation evidence. It never applies a candidate policy. */
public final class JdbcShadowEvaluationStore {
    private final DataSource dataSource;

    public JdbcShadowEvaluationStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public void verifySchema() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement header = connection.prepareStatement(
                     "SELECT run_id FROM andalus_feedback_shadow_evaluation WHERE 1 = 0");
             PreparedStatement metrics = connection.prepareStatement(
                     "SELECT run_id FROM andalus_feedback_shadow_evaluation_metric WHERE 1 = 0");
             PreparedStatement evidence = connection.prepareStatement(
                     "SELECT run_id FROM andalus_feedback_shadow_evaluation_evidence WHERE 1 = 0")) {
            header.executeQuery().close();
            metrics.executeQuery().close();
            evidence.executeQuery().close();
        } catch (SQLException exception) {
            throw new IllegalStateException("Shadow evaluation evidence schema is not ready", exception);
        }
    }

    /**
     * Appends a report only when its approval row exactly authorizes the proposal.
     * Exact run-ID retries are idempotent; a changed payload under the same ID is rejected.
     */
    public ShadowEvaluationReport append(ShadowEvaluationReport report) {
        Objects.requireNonNull(report, "report");
        verifySchema();
        ShadowEvaluationReport existing = findByRunId(report.runId()).orElse(null);
        if (existing != null) return requireExactRetry(existing, report);
        Instant evaluatedAt = report.evaluatedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                verifyApproval(connection, report);
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO andalus_feedback_shadow_evaluation
                            (run_id, proposal_id, subject_kind, subject_id, source_snapshot_id,
                             approval_id, policy_fingerprint, baseline_evaluator_id,
                             baseline_evaluator_version, candidate_evaluator_id,
                             candidate_evaluator_version, dataset_id, dataset_version,
                             dataset_fingerprint, evaluation_status, evaluated_at_epoch_millis)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, report.runId());
                    insert.setString(2, report.proposalId());
                    insert.setString(3, report.subject().kind().name());
                    insert.setString(4, report.subject().id());
                    insert.setString(5, report.sourceSnapshotId());
                    insert.setString(6, report.approvalId());
                    insert.setString(7, report.policyFingerprint());
                    insert.setString(8, report.baselineEvaluatorId());
                    insert.setString(9, report.baselineEvaluatorVersion());
                    insert.setString(10, report.candidateEvaluatorId());
                    insert.setString(11, report.candidateEvaluatorVersion());
                    insert.setString(12, report.datasetId());
                    insert.setString(13, report.datasetVersion());
                    insert.setString(14, report.datasetFingerprint());
                    insert.setString(15, report.status().name());
                    insert.setLong(16, evaluatedAt.toEpochMilli());
                    insert.executeUpdate();
                }
                insertMetrics(connection, report);
                insertEvidence(connection, report);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            } finally {
                try { connection.setAutoCommit(previousAutoCommit); } catch (SQLException ignored) { }
            }
        } catch (SQLException exception) {
            // A concurrent exact retry may win the run-ID insert. Re-read and compare before rejecting.
            ShadowEvaluationReport concurrent = findByRunId(report.runId()).orElse(null);
            if (concurrent != null) return requireExactRetry(concurrent, report);
            throw new IllegalStateException("Unable to append shadow evaluation evidence", exception);
        }
        return withTimestamp(report, evaluatedAt);
    }

    public List<ShadowEvaluationReport> findByProposal(FeedbackAdaptationGate.Proposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        verifySchema();
        List<String> ids = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT run_id FROM andalus_feedback_shadow_evaluation
                     WHERE proposal_id = ? AND subject_kind = ? AND subject_id = ?
                       AND source_snapshot_id = ? AND policy_fingerprint = ?
                     ORDER BY evaluated_at_epoch_millis, run_id
                     """)) {
            query.setString(1, proposal.id());
            query.setString(2, proposal.subject().kind().name());
            query.setString(3, proposal.subject().id());
            query.setString(4, proposal.sourceSnapshotId());
            query.setString(5, proposal.policyFingerprint());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) ids.add(rows.getString(1));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read shadow evaluation history", exception);
        }
        List<ShadowEvaluationReport> reports = new ArrayList<>();
        for (String id : ids) findByRunId(id).ifPresent(reports::add);
        return List.copyOf(reports);
    }

    public java.util.Optional<ShadowEvaluationReport> findByRunId(String runId) {
        Objects.requireNonNull(runId, "runId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT * FROM andalus_feedback_shadow_evaluation WHERE run_id = ?
                     """)) {
            query.setString(1, runId);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return java.util.Optional.empty();
                FeedbackAggregationRecoveryService.Subject subject =
                        new FeedbackAggregationRecoveryService.Subject(
                                FeedbackAggregationRecoveryService.SubjectKind.valueOf(row.getString("subject_kind")),
                                row.getString("subject_id"));
                List<ShadowEvaluationReport.Metric> metrics = readMetrics(connection, runId);
                List<String> evidence = readEvidence(connection, runId);
                return java.util.Optional.of(new ShadowEvaluationReport(
                        row.getString("run_id"), row.getString("proposal_id"), subject,
                        row.getString("source_snapshot_id"), row.getString("approval_id"),
                        row.getString("policy_fingerprint"), row.getString("baseline_evaluator_id"),
                        row.getString("baseline_evaluator_version"), row.getString("candidate_evaluator_id"),
                        row.getString("candidate_evaluator_version"), row.getString("dataset_id"),
                        row.getString("dataset_version"), row.getString("dataset_fingerprint"),
                        ShadowEvaluationReport.Status.valueOf(row.getString("evaluation_status")),
                        metrics, evidence, Instant.ofEpochMilli(row.getLong("evaluated_at_epoch_millis"))));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to recover shadow evaluation report", exception);
        }
    }

    private void verifyApproval(Connection connection, ShadowEvaluationReport report) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT a.proposal_id, a.subject_kind, a.subject_id, a.source_snapshot_id,
                       a.policy_fingerprint, a.decision, r.disposition, r.assessment_decision
                FROM andalus_feedback_adaptation_approval a
                JOIN andalus_feedback_trend_review r ON r.review_id = a.review_id
                WHERE a.approval_id = ?
                """)) {
            query.setString(1, report.approvalId());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()
                        || !"APPROVED".equals(row.getString("decision"))
                        || !"ACCEPTED".equals(row.getString("disposition"))
                        || "INCONCLUSIVE".equals(row.getString("assessment_decision"))
                        || !report.proposalId().equals(row.getString("proposal_id"))
                        || !report.subject().kind().name().equals(row.getString("subject_kind"))
                        || !report.subject().id().equals(row.getString("subject_id"))
                        || !report.sourceSnapshotId().equals(row.getString("source_snapshot_id"))
                        || !report.policyFingerprint().equals(row.getString("policy_fingerprint"))) {
                    throw new IllegalArgumentException("shadow report does not match an accepted, approved proposal");
                }
            }
        }
    }

    private void insertMetrics(Connection connection, ShadowEvaluationReport report) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO andalus_feedback_shadow_evaluation_metric
                    (run_id, metric_order, metric_name, baseline_value, candidate_value,
                     direction, required_improvement) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            int order = 0;
            for (ShadowEvaluationReport.Metric metric : report.metrics()) {
                insert.setString(1, report.runId());
                insert.setInt(2, order++);
                insert.setString(3, metric.name());
                insert.setDouble(4, metric.baselineValue());
                insert.setDouble(5, metric.candidateValue());
                insert.setString(6, metric.direction().name());
                insert.setDouble(7, metric.requiredImprovement());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void insertEvidence(Connection connection, ShadowEvaluationReport report) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO andalus_feedback_shadow_evaluation_evidence
                    (run_id, evidence_order, evidence_reference) VALUES (?, ?, ?)
                """)) {
            int order = 0;
            for (String reference : report.evidenceReferences()) {
                insert.setString(1, report.runId());
                insert.setInt(2, order++);
                insert.setString(3, reference);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private List<ShadowEvaluationReport.Metric> readMetrics(Connection connection, String runId) throws SQLException {
        List<ShadowEvaluationReport.Metric> result = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT metric_name, baseline_value, candidate_value, direction, required_improvement
                FROM andalus_feedback_shadow_evaluation_metric
                WHERE run_id = ? ORDER BY metric_order
                """)) {
            query.setString(1, runId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(new ShadowEvaluationReport.Metric(
                        rows.getString("metric_name"), rows.getDouble("baseline_value"),
                        rows.getDouble("candidate_value"),
                        ShadowEvaluationReport.Direction.valueOf(rows.getString("direction")),
                        rows.getDouble("required_improvement")));
            }
        }
        return List.copyOf(result);
    }

    private List<String> readEvidence(Connection connection, String runId) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT evidence_reference FROM andalus_feedback_shadow_evaluation_evidence
                WHERE run_id = ? ORDER BY evidence_order
                """)) {
            query.setString(1, runId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
        }
        return List.copyOf(result);
    }

    private static ShadowEvaluationReport requireExactRetry(
            ShadowEvaluationReport existing, ShadowEvaluationReport requested) {
        if (!existing.equals(withTimestamp(requested,
                requested.evaluatedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)))) {
            throw new IllegalStateException("run ID is already bound to different shadow evaluation evidence");
        }
        return existing;
    }

    private static ShadowEvaluationReport withTimestamp(ShadowEvaluationReport report, Instant timestamp) {
        return new ShadowEvaluationReport(report.runId(), report.proposalId(), report.subject(),
                report.sourceSnapshotId(), report.approvalId(), report.policyFingerprint(),
                report.baselineEvaluatorId(), report.baselineEvaluatorVersion(),
                report.candidateEvaluatorId(), report.candidateEvaluatorVersion(), report.datasetId(),
                report.datasetVersion(), report.datasetFingerprint(), report.status(), report.metrics(),
                report.evidenceReferences(), timestamp);
    }
}
