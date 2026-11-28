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
                  AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP)
                  AND dead_lettered_at IS NULL
                  AND recovery_attempts < 8
                ORDER BY updated_at ASC, operation_id ASC
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE andalus_anp_key_rotations AS rotation
            SET lease_owner = ?, lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                recovery_attempts = recovery_attempts + 1,
                lease_generation = lease_generation + 1,
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

    /**
     * Claims one known operation so foreground and recovery entry points share
     * the same durable, cross-node exclusion mechanism.
     */
    public boolean claimOperation(String operationId, String workerId, Duration leaseDuration) {
        if (operationId == null || operationId.isBlank() || workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("operationId and workerId must not be blank");
        }
        long seconds = Objects.requireNonNull(leaseDuration, "leaseDuration").toSeconds();
        if (seconds < 1 || seconds > 3600) {
            throw new IllegalArgumentException("leaseDuration must be between 1 second and 1 hour");
        }
        String sql = """
                UPDATE andalus_anp_key_rotations
                SET lease_owner = ?, lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    recovery_attempts = recovery_attempts + 1, lease_generation = lease_generation + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE operation_id = ? AND state <> 'COMPLETED'
                  AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, workerId);
            statement.setLong(2, seconds);
            statement.setString(3, operationId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to claim ANP key rotation", exception);
        }
    }

    /** Returns the current fencing generation only to the live lease owner. */
    public long leaseGeneration(String operationId, String workerId) {
        String sql = """
                SELECT lease_generation FROM andalus_anp_key_rotations
                WHERE operation_id = ? AND lease_owner = ? AND lease_until > CURRENT_TIMESTAMP
                  AND state <> 'COMPLETED'
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, operationId);
            statement.setString(2, workerId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("No live ANP rotation lease for worker");
                }
                return rows.getLong(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read ANP rotation fencing generation", exception);
        }
    }

    /**
     * Renews a live lease before work. An expired lease cannot be resurrected by
     * its former owner after another worker has become eligible to claim it.
     */
    public boolean renewLease(String operationId, String workerId, long generation, Duration leaseDuration) {
        if (operationId == null || operationId.isBlank() || workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("operationId and workerId must not be blank");
        }
        long seconds = Objects.requireNonNull(leaseDuration, "leaseDuration").toSeconds();
        if (seconds < 1 || seconds > 3600) {
            throw new IllegalArgumentException("leaseDuration must be between 1 second and 1 hour");
        }
        String sql = """
                UPDATE andalus_anp_key_rotations
                SET lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE operation_id = ? AND lease_owner = ? AND lease_generation = ?
                  AND lease_until > CURRENT_TIMESTAMP AND state <> 'COMPLETED'
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, seconds);
            statement.setString(2, operationId);
            statement.setString(3, workerId);
            statement.setLong(4, generation);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to renew ANP key rotation lease", exception);
        }
    }

    /** Verifies lease ownership and expiry before a coordinator side effect. */
    public boolean ownsLease(String operationId, String workerId, long generation) {
        String sql = """
                SELECT 1 FROM andalus_anp_key_rotations
                WHERE operation_id = ? AND lease_owner = ? AND lease_generation = ?
                  AND lease_until > CURRENT_TIMESTAMP AND state <> 'COMPLETED'
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, operationId);
            statement.setString(2, workerId);
            statement.setLong(3, generation);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to verify ANP key rotation lease", exception);
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


    /**
     * Records a failed recovery attempt and schedules bounded exponential backoff.
     * Only the current lease owner and fencing generation may report the failure.
     * Exception messages are deliberately not persisted because they may contain secrets.
     */
    public boolean recordFailure(String operationId, String workerId, long generation, String failureType) {
        if (operationId == null || operationId.isBlank() || workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("operationId and workerId must not be blank");
        }
        if (failureType == null || !failureType.matches("[A-Za-z0-9_$]{1,160}")) {
            throw new IllegalArgumentException("failureType must be a safe exception type name");
        }
        String sql = """
                UPDATE andalus_anp_key_rotations
                SET last_failure_at = CURRENT_TIMESTAMP,
                    last_failure_type = ?,
                    next_attempt_at = CASE
                        WHEN recovery_attempts >= 8 THEN NULL
                        ELSE CURRENT_TIMESTAMP + (
                            LEAST(300, (5 * POWER(2, GREATEST(0, recovery_attempts - 1)))) * INTERVAL '1 second'
                        )
                    END,
                    dead_lettered_at = CASE
                        WHEN recovery_attempts >= 8 THEN CURRENT_TIMESTAMP
                        ELSE NULL
                    END,
                    lease_owner = NULL, lease_until = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE operation_id = ? AND lease_owner = ? AND lease_generation = ?
                  AND state <> 'COMPLETED'
                """;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, failureType);
                statement.setString(2, operationId);
                statement.setString(3, workerId);
                statement.setLong(4, generation);
                int changed = statement.executeUpdate();
                if (changed == 1) {
                    PostgresAnpKeyRotationAuditLog.append(connection, operationId, "RECOVERY_FAILED",
                            null, workerId, null, null, failureType);
                }
                connection.commit();
                return changed == 1;
            } catch (SQLException | RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to schedule ANP key rotation retry", exception);
        }
    }

    /** Clears retry metadata after successful progress without changing the durable attempt count. */
    public boolean recordSuccess(String operationId, String workerId, long generation) {
        String sql = """
                UPDATE andalus_anp_key_rotations
                SET next_attempt_at = NULL, last_failure_at = NULL, last_failure_type = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE operation_id = ? AND lease_owner = ? AND lease_generation = ?
                  AND lease_until > CURRENT_TIMESTAMP AND state <> 'COMPLETED'
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, operationId);
            statement.setString(2, workerId);
            statement.setLong(3, generation);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to clear ANP key rotation retry metadata", exception);
        }
    }

    /** Explicit operator replay clears dead-letter state; normal recovery never does this implicitly. */
    public boolean requeueDeadLetter(String operationId) {
        String sql = """
                UPDATE andalus_anp_key_rotations
                SET dead_lettered_at = NULL, recovery_attempts = 0,
                    next_attempt_at = CURRENT_TIMESTAMP,
                    last_failure_at = NULL, last_failure_type = NULL,
                    lease_owner = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE operation_id = ? AND state <> 'COMPLETED' AND dead_lettered_at IS NOT NULL
                """;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, operationId);
                int changed = statement.executeUpdate();
                if (changed == 1) {
                    PostgresAnpKeyRotationAuditLog.append(connection, operationId, "DEAD_LETTER_REQUEUED",
                            null, "operator", null, null, null);
                }
                connection.commit();
                return changed == 1;
            } catch (SQLException | RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to requeue dead-lettered ANP key rotation", exception);
        }
    }

    /** Operational counters; all values are computed from the durable journal. */
    public RecoveryStatus recoveryStatus() {
        String sql = """
                SELECT COUNT(*) FILTER (WHERE state <> 'COMPLETED' AND dead_lettered_at IS NULL) AS pending,
                       COUNT(*) FILTER (WHERE state <> 'COMPLETED' AND lease_until > CURRENT_TIMESTAMP) AS leased,
                       COUNT(*) FILTER (WHERE state <> 'COMPLETED' AND dead_lettered_at IS NULL
                           AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP)
                           AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)) AS due,
                       COUNT(*) FILTER (WHERE state <> 'COMPLETED' AND dead_lettered_at IS NOT NULL) AS dead_lettered,
                       MIN(created_at) FILTER (WHERE state <> 'COMPLETED') AS oldest_pending_at
                FROM andalus_anp_key_rotations
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            java.sql.Timestamp oldest = rows.getTimestamp("oldest_pending_at");
            return new RecoveryStatus(rows.getLong("pending"), rows.getLong("leased"),
                    rows.getLong("due"), rows.getLong("dead_lettered"),
                    oldest == null ? null : oldest.toInstant());
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read ANP key rotation recovery status", exception);
        }
    }

    public record RecoveryStatus(long pending, long leased, long due, long deadLettered,
                                 java.time.Instant oldestPendingAt) { }

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
