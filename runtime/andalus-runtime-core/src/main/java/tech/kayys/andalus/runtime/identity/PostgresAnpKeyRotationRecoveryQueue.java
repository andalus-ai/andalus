package tech.kayys.andalus.runtime.identity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded discovery and lease-based claiming for incomplete ANP key rotations.
 *
 * <p>Claims use PostgreSQL row locks with SKIP LOCKED, so concurrent workers do not
 * receive the same live lease. Expired leases are reclaimable after worker failure.
 * The journal state machine remains the authority for side-effect ordering.
 */
@ApplicationScoped
public class PostgresAnpKeyRotationRecoveryQueue {
    private static final String PENDING = """
            SELECT operation_id, did, actor_id, old_key_id, new_key_id,
                   expected_revision, target_revision, state, created_at, updated_at
            FROM andalus_anp_key_rotations
            WHERE state <> 'COMPLETED'
            ORDER BY updated_at ASC, operation_id ASC
            LIMIT ?
            """;

    private static final String CLAIM = """
            WITH candidates AS (
                SELECT operation_id
                FROM andalus_anp_key_rotations
                WHERE state <> 'COMPLETED'
                  AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                ORDER BY updated_at ASC, operation_id ASC
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE andalus_anp_key_rotations AS rotation
            SET lease_owner = ?, lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                recovery_attempts = recovery_attempts + 1,
                updated_at = CURRENT_TIMESTAMP
            FROM candidates
            WHERE rotation.operation_id = candidates.operation_id
            RETURNING rotation.operation_id, rotation.did, rotation.actor_id,
                      rotation.old_key_id, rotation.new_key_id,
                      rotation.expected_revision, rotation.target_revision,
                      rotation.state, rotation.created_at, rotation.updated_at
            """;

    private static final String RELEASE = """
            UPDATE andalus_anp_key_rotations
            SET lease_owner = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE operation_id = ? AND lease_owner = ? AND state <> 'COMPLETED'
            """;

    private final DataSource dataSource;

    @Inject
    public PostgresAnpKeyRotationRecoveryQueue(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Returns oldest incomplete operations for diagnostics; does not acquire ownership. */
    public List<PostgresAnpKeyRotationJournal.Rotation> findPending(int limit) {
        requireLimit(limit);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(PENDING)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                return readRows(rows);
            }
        } catch (SQLException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to scan pending ANP key rotations", exception);
        }
    }

    /**
     * Atomically claims up to {@code limit} incomplete operations for one worker.
     * A crashed worker's claims become available when the lease expires.
     */
    public List<PostgresAnpKeyRotationJournal.Rotation> claimPending(
            String workerId, Duration leaseDuration, int limit) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        requireLimit(limit);
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        long leaseSeconds = leaseDuration.toSeconds();
        if (leaseSeconds < 1 || leaseSeconds > 3600) {
            throw new IllegalArgumentException("leaseDuration must be between 1 second and 1 hour");
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(CLAIM)) {
                statement.setInt(1, limit);
                statement.setString(2, workerId);
                statement.setLong(3, leaseSeconds);
                try (ResultSet rows = statement.executeQuery()) {
                    List<PostgresAnpKeyRotationJournal.Rotation> claimed = readRows(rows);
                    connection.commit();
                    return claimed;
                }
            } catch (SQLException | RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to claim pending ANP key rotations", exception);
        }
    }

    /** Releases only a lease owned by this worker; stale workers cannot release another worker's claim. */
    public boolean release(String operationId, String workerId) {
        if (operationId == null || operationId.isBlank() || workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("operationId and workerId must not be blank");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(RELEASE)) {
            statement.setString(1, operationId);
            statement.setString(2, workerId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to release ANP key rotation lease", exception);
        }
    }

    private static List<PostgresAnpKeyRotationJournal.Rotation> readRows(ResultSet rows)
            throws SQLException {
        List<PostgresAnpKeyRotationJournal.Rotation> result = new ArrayList<>();
        while (rows.next()) {
            result.add(new PostgresAnpKeyRotationJournal.Rotation(
                    rows.getString("operation_id"), rows.getString("did"),
                    rows.getString("actor_id"), rows.getString("old_key_id"),
                    rows.getString("new_key_id"), rows.getLong("expected_revision"),
                    rows.getLong("target_revision"),
                    PostgresAnpKeyRotationJournal.RotationState.valueOf(rows.getString("state")),
                    rows.getTimestamp("created_at").toInstant(),
                    rows.getTimestamp("updated_at").toInstant()));
        }
        return List.copyOf(result);
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Recovery batch limit must be between 1 and 500");
        }
    }
}
