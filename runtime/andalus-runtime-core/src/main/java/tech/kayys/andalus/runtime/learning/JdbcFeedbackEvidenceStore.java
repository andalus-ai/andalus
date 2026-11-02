package tech.kayys.andalus.runtime.learning;

import tech.kayys.andalus.harness.learning.FeedbackEvidenceStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC-backed, cross-process feedback evidence reservation store.
 *
 * <p>Apply the versioned SQL migration under
 * {@code src/main/resources/db/migration} before admitting feedback.
 * {@link #initializeSchema()} is a convenience bootstrap for tests and local
 * development, not a production migration mechanism. The table uses database
 * uniqueness constraints as the concurrency authority; JVM locks are
 * deliberately not used. All references in a batch are inserted in one transaction.</p>
 *
 * <p>Use a shared transactional database and a DataSource configured for the
 * required durability and isolation guarantees. This adapter does not select
 * a vendor, configure credentials, or run migrations automatically.</p>
 */
public final class JdbcFeedbackEvidenceStore implements FeedbackEvidenceStore {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS andalus_feedback_evidence (
                evidence_reference VARCHAR(2048) NOT NULL PRIMARY KEY,
                signal_id VARCHAR(255) NOT NULL,
                reference_order INTEGER NOT NULL,
                CONSTRAINT uq_feedback_signal_order UNIQUE (signal_id, reference_order)
            )
            """;

    private static final String SELECT_SIGNAL = """
            SELECT evidence_reference
            FROM andalus_feedback_evidence
            WHERE signal_id = ?
            ORDER BY reference_order
            """;

    private final DataSource dataSource;

    public JdbcFeedbackEvidenceStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource is required");
    }

    /**
     * Creates the minimal table if absent. Production deployments should
     * manage this DDL through their normal migration mechanism instead.
     */
    public void initializeSchema() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(CREATE_TABLE)) {
            statement.execute();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to initialize feedback evidence schema", exception);
        }
    }

    /**
     * Performs a read-only readiness probe. It deliberately does not create or
     * modify schema; production migrations remain deployment-owned.
     *
     * @throws IllegalStateException when the database or required table is unavailable
     */
    public void verifySchema() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT evidence_reference FROM andalus_feedback_evidence WHERE 1 = 0")) {
            statement.executeQuery().close();
        } catch (SQLException exception) {
            throw new IllegalStateException("Feedback evidence store is not ready", exception);
        }
    }

    @Override
    public Reservation reserve(String signalId, List<String> evidenceReferences) {
        if (signalId == null || signalId.isBlank() || signalId.length() > 255
                || evidenceReferences == null || evidenceReferences.isEmpty()
                || evidenceReferences.stream().anyMatch(ref -> ref == null || ref.isBlank() || ref.length() > 2048)
                || evidenceReferences.stream().distinct().count() != evidenceReferences.size()) {
            return new Reservation(signalId, evidenceReferences, ReservationStatus.INVALID_REQUEST);
        }

        List<String> requested = List.copyOf(evidenceReferences);
        SQLException failure = null;
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<String> existing = findSignalReferences(connection, signalId);
                if (!existing.isEmpty()) {
                    connection.rollback();
                    return new Reservation(signalId, requested,
                            existing.equals(requested)
                                    ? ReservationStatus.ALREADY_RESERVED_BY_SAME_SIGNAL
                                    : ReservationStatus.EVIDENCE_ALREADY_CONSUMED);
                }

                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO andalus_feedback_evidence
                            (evidence_reference, signal_id, reference_order)
                        VALUES (?, ?, ?)
                        """)) {
                    for (int index = 0; index < requested.size(); index++) {
                        insert.setString(1, requested.get(index));
                        insert.setString(2, signalId);
                        insert.setInt(3, index);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
                return new Reservation(signalId, requested, ReservationStatus.RESERVED);
            } catch (SQLException exception) {
                failure = exception;
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // The connection is closed immediately after this method.
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to reserve feedback evidence", exception);
        }

        if (isConstraintViolation(failure)) {
            // A concurrent transaction may have won. Re-read after rollback to
            // distinguish an exact idempotent retry from a conflicting replay.
            List<String> committed = findSignalReferences(signalId);
            if (committed.equals(requested)) {
                return new Reservation(signalId, requested,
                        ReservationStatus.ALREADY_RESERVED_BY_SAME_SIGNAL);
            }
            return new Reservation(signalId, requested, ReservationStatus.EVIDENCE_ALREADY_CONSUMED);
        }
        throw new IllegalStateException("Unable to reserve feedback evidence", failure);
    }

    @Override
    public Optional<String> findOwner(String evidenceReference) {
        if (evidenceReference == null || evidenceReference.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT signal_id
                     FROM andalus_feedback_evidence
                     WHERE evidence_reference = ?
                     """)) {
            statement.setString(1, evidenceReference);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to resolve feedback evidence owner", exception);
        }
    }

    private List<String> findSignalReferences(String signalId) {
        try (Connection connection = dataSource.getConnection()) {
            return findSignalReferences(connection, signalId);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to inspect feedback evidence reservation", exception);
        }
    }

    private List<String> findSignalReferences(Connection connection, String signalId) throws SQLException {
        List<String> references = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SELECT_SIGNAL)) {
            statement.setString(1, signalId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    references.add(result.getString(1));
                }
            }
        }
        return references;
    }

    private boolean isConstraintViolation(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith("23")) {
                return true;
            }
        }
        return false;
    }
}
