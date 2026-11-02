package tech.kayys.andalus.runtime.learning;

import tech.kayys.andalus.harness.learning.FeedbackAggregation;
import tech.kayys.andalus.harness.learning.FeedbackTrustPolicy;
import tech.kayys.andalus.harness.learning.LearningSignal;
import tech.kayys.andalus.harness.learning.LearningSignalPolarity;
import tech.kayys.andalus.harness.learning.LearningSignalType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Transactional JDBC ledger for admitted human feedback.
 *
 * <p>The signal row and all evidence reservations commit or roll back together.
 * Only typed provenance fields are persisted; LearningSignal.metadata is
 * deliberately excluded because it is an arbitrary, potentially sensitive map.</p>
 */
public final class JdbcAdmittedFeedbackLedger {
    private final DataSource dataSource;

    public JdbcAdmittedFeedbackLedger(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource is required");
    }

    public void verifySchema() {
        try (Connection connection = dataSource.getConnection()) {
            for (String table : List.of("andalus_feedback_evidence", "andalus_admitted_feedback")) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT signal_id FROM " + table + " WHERE 1 = 0")) {
                    statement.executeQuery().close();
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Admitted feedback ledger is not ready", exception);
        }
    }

    /** Trust-checks first, then atomically reserves evidence and persists provenance. */
    public FeedbackAggregation.AdmissionResult admit(
            LearningSignal signal,
            FeedbackTrustPolicy.Policy trustPolicy,
            Set<String> corroboratingSources) {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(trustPolicy, "trustPolicy");
        Set<String> corroboration = corroboratingSources == null ? Set.of() : Set.copyOf(corroboratingSources);
        Optional<FeedbackAggregation.AdmittedFeedback> trusted =
                FeedbackAggregation.admit(signal, trustPolicy, Set.of(), corroboration);
        if (trusted.isEmpty()) {
            var decision = FeedbackTrustPolicy.assess(signal, trustPolicy, Set.of(), corroboration);
            return new FeedbackAggregation.AdmissionResult(Optional.empty(), decision.reasons());
        }
        if (!valid(signal)) {
            return rejected("feedback signal exceeds ledger field limits or has no evidence");
        }

        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<String> existing = references(connection, signal.id());
                if (!existing.isEmpty()) {
                    boolean same = existing.equals(signal.evidenceReferences())
                            && sameSignal(connection, signal);
                    connection.rollback();
                    return same
                            ? new FeedbackAggregation.AdmissionResult(trusted, List.of())
                            : rejected("feedback signal identifier is already reserved");
                }

                insertEvidence(connection, signal);
                insertSignal(connection, signal);
                connection.commit();
                return new FeedbackAggregation.AdmissionResult(trusted, List.of());
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                if (exception instanceof SQLException sql && isConstraintViolation(sql)) {
                    return rejected("feedback evidence is already consumed");
                }
                throw exception;
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // Connection is closed immediately after this method.
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to persist admitted feedback", exception);
        }
    }

    /** Recovers admitted signals in stable occurred-at / signal-id order. */
    public List<LearningSignal> findAll() {
        String sql = """
                SELECT signal_id, signal_type, source_id, execution_id, polarity,
                       turn_id, goal_id, task_id, evaluation_reference, occurred_at
                FROM andalus_admitted_feedback
                ORDER BY occurred_at, signal_id
                """;
        List<SignalRow> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(new SignalRow(
                        result.getString("signal_id"), result.getString("signal_type"),
                        result.getString("source_id"), result.getString("execution_id"),
                        result.getString("polarity"), result.getString("turn_id"),
                        result.getString("goal_id"), result.getString("task_id"),
                        result.getString("evaluation_reference"), result.getString("occurred_at")));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to recover admitted feedback", exception);
        }

        List<LearningSignal> signals = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (SignalRow row : rows) {
                signals.add(new LearningSignal(
                        row.id(), LearningSignalType.valueOf(row.type()), row.source(), row.executionId(),
                        Optional.ofNullable(row.turnId()), Optional.ofNullable(row.goalId()),
                        Optional.ofNullable(row.taskId()), LearningSignalPolarity.valueOf(row.polarity()),
                        references(connection, row.id()), Optional.ofNullable(row.evaluationReference()),
                        Instant.parse(row.occurredAt()), java.util.Map.of()));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to recover admitted feedback evidence", exception);
        }
        return List.copyOf(signals);
    }

    private record SignalRow(String id, String type, String source, String executionId,
                             String polarity, String turnId, String goalId, String taskId,
                             String evaluationReference, String occurredAt) {
    }

    private boolean sameSignal(Connection connection, LearningSignal signal) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT signal_type, source_id, execution_id, polarity, turn_id, goal_id,
                       task_id, evaluation_reference, occurred_at
                FROM andalus_admitted_feedback WHERE signal_id = ?
                """)) {
            statement.setString(1, signal.id());
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        && signal.type().name().equals(row.getString(1))
                        && signal.source().equals(row.getString(2))
                        && signal.executionId().equals(row.getString(3))
                        && signal.polarity().name().equals(row.getString(4))
                        && Objects.equals(signal.turnId().orElse(null), row.getString(5))
                        && Objects.equals(signal.goalId().orElse(null), row.getString(6))
                        && Objects.equals(signal.taskId().orElse(null), row.getString(7))
                        && Objects.equals(signal.evaluationReference().orElse(null), row.getString(8))
                        && signal.occurredAt().toString().equals(row.getString(9));
            }
        }
    }

    private List<String> references(Connection connection, String signalId) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT evidence_reference FROM andalus_feedback_evidence
                WHERE signal_id = ? ORDER BY reference_order
                """)) {
            statement.setString(1, signalId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) values.add(rows.getString(1));
            }
        }
        return values;
    }

    private void insertEvidence(Connection connection, LearningSignal signal) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO andalus_feedback_evidence
                    (evidence_reference, signal_id, reference_order) VALUES (?, ?, ?)
                """)) {
            for (int i = 0; i < signal.evidenceReferences().size(); i++) {
                statement.setString(1, signal.evidenceReferences().get(i));
                statement.setString(2, signal.id());
                statement.setInt(3, i);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertSignal(Connection connection, LearningSignal signal) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO andalus_admitted_feedback
                    (signal_id, signal_type, source_id, execution_id, polarity, turn_id,
                     goal_id, task_id, evaluation_reference, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, signal.id());
            statement.setString(2, signal.type().name());
            statement.setString(3, signal.source());
            statement.setString(4, signal.executionId());
            statement.setString(5, signal.polarity().name());
            statement.setString(6, signal.turnId().orElse(null));
            statement.setString(7, signal.goalId().orElse(null));
            statement.setString(8, signal.taskId().orElse(null));
            statement.setString(9, signal.evaluationReference().orElse(null));
            statement.setString(10, signal.occurredAt().toString());
            statement.executeUpdate();
        }
    }

    private boolean valid(LearningSignal signal) {
        return signal.id().length() <= 255 && signal.source().length() <= 255
                && signal.executionId().length() <= 255
                && signal.turnId().map(v -> v.length() <= 255).orElse(true)
                && signal.goalId().map(v -> v.length() <= 255).orElse(true)
                && signal.taskId().map(v -> v.length() <= 255).orElse(true)
                && signal.evaluationReference().map(v -> v.length() <= 2048).orElse(true)
                && !signal.evidenceReferences().isEmpty()
                && signal.evidenceReferences().stream().allMatch(v -> v != null && !v.isBlank() && v.length() <= 2048)
                && signal.evidenceReferences().stream().distinct().count() == signal.evidenceReferences().size();
    }

    private boolean isConstraintViolation(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            if (current.getSQLState() != null && current.getSQLState().startsWith("23")) return true;
        }
        return false;
    }

    private static FeedbackAggregation.AdmissionResult rejected(String reason) {
        return new FeedbackAggregation.AdmissionResult(Optional.empty(), List.of(reason));
    }
}
