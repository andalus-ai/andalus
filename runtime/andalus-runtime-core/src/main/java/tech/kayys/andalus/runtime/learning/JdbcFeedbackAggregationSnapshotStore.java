package tech.kayys.andalus.runtime.learning;

import tech.kayys.andalus.harness.learning.FeedbackAggregation;

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

/** JDBC persistence for append-only advisory aggregation history. */
public final class JdbcFeedbackAggregationSnapshotStore {
    private final DataSource dataSource;

    public JdbcFeedbackAggregationSnapshotStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource is required");
    }

    public void verifySchema() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT snapshot_id FROM andalus_feedback_aggregation_snapshot WHERE 1 = 0")) {
            statement.executeQuery().close();
            try (PreparedStatement items = connection.prepareStatement(
                    "SELECT snapshot_id FROM andalus_feedback_aggregation_snapshot_item WHERE 1 = 0")) {
                items.executeQuery().close();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Feedback aggregation snapshot schema is not ready", exception);
        }
    }

    /** Persists one immutable evaluation and all of its provenance in a single transaction. */
    public Snapshot save(FeedbackAggregationRecoveryService.AdvisoryReport report) {
        Objects.requireNonNull(report, "report");
        validate(report);
        verifySchema();
        String snapshotId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        FeedbackAggregation.Result result = report.aggregation();

        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO andalus_feedback_aggregation_snapshot
                            (snapshot_id, subject_kind, subject_id, outcome, positive_weight,
                             negative_weight, ambiguous_weight, positive_ratio, negative_ratio,
                             distinct_sources, evidence_references, trust_policy_fingerprint,
                             aggregation_policy_fingerprint, corroboration_fingerprint, created_at_epoch_millis)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, snapshotId);
                    statement.setString(2, report.subject().kind().name());
                    statement.setString(3, report.subject().id());
                    statement.setString(4, result.outcome().name());
                    statement.setDouble(5, result.positiveWeight());
                    statement.setDouble(6, result.negativeWeight());
                    statement.setDouble(7, result.ambiguousWeight());
                    statement.setDouble(8, result.positiveRatio());
                    statement.setDouble(9, result.negativeRatio());
                    statement.setInt(10, result.distinctSources());
                    statement.setInt(11, result.evidenceReferences());
                    statement.setString(12, report.trustPolicyFingerprint());
                    statement.setString(13, report.aggregationPolicyFingerprint());
                    statement.setString(14, report.corroborationFingerprint());
                    statement.setLong(15, createdAt.toEpochMilli());
                    statement.executeUpdate();
                }

                insertItems(connection, snapshotId, "CONSIDERED_SIGNAL", 0,
                        report.consideredSignalIds());
                insertItems(connection, snapshotId, "ADMITTED_SIGNAL", 0, report.signalIds());
                insertItems(connection, snapshotId, "SOURCE", 0, report.sourceIds());
                insertItems(connection, snapshotId, "EVIDENCE", 0, report.evidenceReferences());
                insertItems(connection, snapshotId, "REASON", 0, result.reasons());
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
                    // Connection closes at method exit.
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to persist feedback aggregation snapshot", exception);
        }
        return new Snapshot(snapshotId, report.subject(), result, report.trustPolicyFingerprint(),
                report.aggregationPolicyFingerprint(), report.corroborationFingerprint(),
                report.consideredSignalIds(), report.signalIds(), report.sourceIds(),
                report.evidenceReferences(), createdAt);
    }

    /** Returns append-only history, oldest first, for the exact typed subject. */
    public List<Snapshot> findBySubject(FeedbackAggregationRecoveryService.Subject subject) {
        Objects.requireNonNull(subject, "subject");
        List<SnapshotHeader> headers = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT snapshot_id, outcome, positive_weight, negative_weight, ambiguous_weight,
                            positive_ratio, negative_ratio, distinct_sources, evidence_references,
                            trust_policy_fingerprint, aggregation_policy_fingerprint,
                            corroboration_fingerprint, created_at_epoch_millis
                     FROM andalus_feedback_aggregation_snapshot
                     WHERE subject_kind = ? AND subject_id = ?
                     ORDER BY created_at_epoch_millis, snapshot_id
                     """)) {
            statement.setString(1, subject.kind().name());
            statement.setString(2, subject.id());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    headers.add(new SnapshotHeader(
                            rows.getString("snapshot_id"), rows.getString("outcome"),
                            rows.getDouble("positive_weight"), rows.getDouble("negative_weight"),
                            rows.getDouble("ambiguous_weight"), rows.getDouble("positive_ratio"),
                            rows.getDouble("negative_ratio"), rows.getInt("distinct_sources"),
                            rows.getInt("evidence_references"), rows.getString("trust_policy_fingerprint"),
                            rows.getString("aggregation_policy_fingerprint"),
                            rows.getString("corroboration_fingerprint"), rows.getLong("created_at_epoch_millis")));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read feedback aggregation history", exception);
        }

        List<Snapshot> snapshots = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (SnapshotHeader header : headers) {
                List<String> considered = items(connection, header.id(), "CONSIDERED_SIGNAL");
                List<String> admitted = items(connection, header.id(), "ADMITTED_SIGNAL");
                List<String> sources = items(connection, header.id(), "SOURCE");
                List<String> evidence = items(connection, header.id(), "EVIDENCE");
                List<String> reasons = items(connection, header.id(), "REASON");
                FeedbackAggregation.Result result = new FeedbackAggregation.Result(
                        subject.key(), FeedbackAggregation.Outcome.valueOf(header.outcome()),
                        header.positiveWeight(), header.negativeWeight(), header.ambiguousWeight(),
                        header.positiveRatio(), header.negativeRatio(), header.distinctSources(),
                        header.evidenceReferences(), reasons);
                snapshots.add(new Snapshot(header.id(), subject, result,
                        header.trustFingerprint(), header.aggregationFingerprint(),
                        header.corroborationFingerprint(), considered, admitted, sources, evidence,
                        Instant.ofEpochMilli(header.createdAtEpochMillis())));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read feedback aggregation provenance", exception);
        }
        return List.copyOf(snapshots);
    }

    private int insertItems(Connection connection, String snapshotId, String kind,
                            int startOrder, List<String> values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO andalus_feedback_aggregation_snapshot_item
                    (snapshot_id, item_kind, item_order, item_value) VALUES (?, ?, ?, ?)
                """)) {
            int order = startOrder;
            for (String value : values) {
                statement.setString(1, snapshotId);
                statement.setString(2, kind);
                statement.setInt(3, order++);
                statement.setString(4, value);
                statement.addBatch();
            }
            statement.executeBatch();
            return order;
        }
    }

    private List<String> items(Connection connection, String snapshotId, String kind) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT item_value FROM andalus_feedback_aggregation_snapshot_item
                WHERE snapshot_id = ? AND item_kind = ? ORDER BY item_order
                """)) {
            statement.setString(1, snapshotId);
            statement.setString(2, kind);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) values.add(rows.getString(1));
            }
        }
        return List.copyOf(values);
    }

    private void validate(FeedbackAggregationRecoveryService.AdvisoryReport report) {
        if (report.subject().id().length() > 255) {
            throw new IllegalArgumentException("snapshot subject identifier exceeds 255 characters");
        }
        for (List<String> values : List.of(report.consideredSignalIds(), report.signalIds(),
                report.sourceIds(), report.evidenceReferences(), report.aggregation().reasons())) {
            for (String value : values) {
                if (value == null || value.length() > 2048) {
                    throw new IllegalArgumentException("snapshot provenance item exceeds 2048 characters");
                }
            }
        }
    }

    private record SnapshotHeader(String id, String outcome, double positiveWeight,
                                  double negativeWeight, double ambiguousWeight,
                                  double positiveRatio, double negativeRatio, int distinctSources,
                                  int evidenceReferences, String trustFingerprint,
                                  String aggregationFingerprint, String corroborationFingerprint,
                                  long createdAtEpochMillis) {
    }

    public record Snapshot(String id, FeedbackAggregationRecoveryService.Subject subject,
                           FeedbackAggregation.Result aggregation, String trustPolicyFingerprint,
                           String aggregationPolicyFingerprint, String corroborationFingerprint,
                           List<String> consideredSignalIds, List<String> signalIds,
                           List<String> sourceIds, List<String> evidenceReferences, Instant createdAt) {
        public Snapshot {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(aggregation, "aggregation");
            Objects.requireNonNull(createdAt, "createdAt");
            consideredSignalIds = List.copyOf(consideredSignalIds);
            signalIds = List.copyOf(signalIds);
            sourceIds = List.copyOf(sourceIds);
            evidenceReferences = List.copyOf(evidenceReferences);
        }
    }
}
