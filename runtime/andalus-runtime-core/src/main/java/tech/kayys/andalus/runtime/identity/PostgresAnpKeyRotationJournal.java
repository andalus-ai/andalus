package tech.kayys.andalus.runtime.identity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable compare-and-set journal for ANP signing-key rotation.
 *
 * <p>Callers must authenticate and authorize {@code actorId} before preparing an operation.
 * Each external side effect is performed only after its preceding journal transition commits.
 * Recovery workers can scan non-completed rows and resume idempotently. This journal does not
 * make the DID store and key store one transaction; authentication must remain fail-closed
 * whenever those stores disagree.
 */
@ApplicationScoped
public class PostgresAnpKeyRotationJournal {
    private static final String INSERT = """
            INSERT INTO andalus_anp_key_rotations
                (operation_id, did, actor_id, old_key_id, new_key_id,
                 expected_revision, target_revision, state)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'PREPARED')
            ON CONFLICT (operation_id) DO NOTHING
            """;

    private static final String FIND = """
            SELECT operation_id, did, actor_id, old_key_id, new_key_id,
                   expected_revision, target_revision, state, created_at, updated_at
            FROM andalus_anp_key_rotations WHERE operation_id = ?
            """;

    private static final String ADVANCE = """
            UPDATE andalus_anp_key_rotations
            SET state = ?, updated_at = CURRENT_TIMESTAMP
            WHERE operation_id = ? AND state = ?
            """;

    private final DataSource dataSource;

    @Inject
    public PostgresAnpKeyRotationJournal(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Creates a PREPARED operation, or returns the exact existing idempotent replay. */
    public Rotation findOrPrepare(RotationRequest request) {
        Objects.requireNonNull(request, "request");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
                    insert.setString(1, request.operationId());
                    insert.setString(2, request.did());
                    insert.setString(3, request.actorId());
                    insert.setString(4, request.oldKeyId());
                    insert.setString(5, request.newKeyId());
                    insert.setLong(6, request.expectedRevision());
                    insert.setLong(7, request.targetRevision());
                    insert.executeUpdate();
                }
                Rotation existing = find(connection, request.operationId())
                        .orElseThrow(() -> new IllegalStateException("Rotation journal row missing after prepare"));
                if (!existing.matches(request)) {
                    throw new SecurityException("Rotation operation ID was reused with different parameters");
                }
                connection.commit();
                return existing;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to prepare ANP key rotation", exception);
        }
    }

    /** Advances exactly one legal step; replaying the same target state is idempotent. */
    public Rotation advance(String operationId, RotationState expected, RotationState next) {
        Objects.requireNonNull(operationId, "operationId");
        requireNext(expected, next);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement update = connection.prepareStatement(ADVANCE)) {
                    update.setString(1, next.name());
                    update.setString(2, operationId);
                    update.setString(3, expected.name());
                    int changed = update.executeUpdate();
                    if (changed == 0) {
                        Rotation current = find(connection, operationId)
                                .orElseThrow(() -> new IllegalArgumentException("Unknown rotation: " + operationId));
                        if (current.state() != next) {
                            throw new IllegalStateException("Rotation state conflict: expected "
                                    + expected + " but found " + current.state());
                        }
                    }
                }
                Rotation result = find(connection, operationId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown rotation: " + operationId));
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to advance ANP key rotation", exception);
        }
    }

    public Optional<Rotation> find(String operationId) {
        try (Connection connection = dataSource.getConnection()) {
            return find(connection, operationId);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read ANP key rotation", exception);
        }
    }

    private Optional<Rotation> find(Connection connection, String operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND)) {
            statement.setString(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new Rotation(
                        result.getString("operation_id"), result.getString("did"),
                        result.getString("actor_id"), result.getString("old_key_id"),
                        result.getString("new_key_id"), result.getLong("expected_revision"),
                        result.getLong("target_revision"),
                        RotationState.valueOf(result.getString("state")),
                        result.getTimestamp("created_at").toInstant(),
                        result.getTimestamp("updated_at").toInstant()));
            }
        }
    }

    private static void requireNext(RotationState current, RotationState next) {
        if (current == null || next == null || current.next() != next) {
            throw new IllegalArgumentException("Illegal rotation transition: " + current + " -> " + next);
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); }
        catch (SQLException failure) { original.addSuppressed(failure); }
    }

    public enum RotationState {
        PREPARED, DID_DOCUMENT_UPDATED, NEW_KEY_ACTIVATED, OLD_KEY_REVOKED, COMPLETED;

        RotationState next() {
            return switch (this) {
                case PREPARED -> DID_DOCUMENT_UPDATED;
                case DID_DOCUMENT_UPDATED -> NEW_KEY_ACTIVATED;
                case NEW_KEY_ACTIVATED -> OLD_KEY_REVOKED;
                case OLD_KEY_REVOKED -> COMPLETED;
                case COMPLETED -> null;
            };
        }
    }

    public record RotationRequest(String operationId, String did, String actorId,
                                  String oldKeyId, String newKeyId,
                                  long expectedRevision, long targetRevision) {
        public RotationRequest {
            requireText(operationId, "operationId");
            requireText(did, "did");
            requireText(actorId, "actorId");
            requireText(oldKeyId, "oldKeyId");
            requireText(newKeyId, "newKeyId");
            if (oldKeyId.equals(newKeyId)) throw new IllegalArgumentException("Rotation requires a new key ID");
            if (expectedRevision < 1 || targetRevision <= expectedRevision)
                throw new IllegalArgumentException("targetRevision must exceed a positive expectedRevision");
        }
    }

    public record Rotation(String operationId, String did, String actorId,
                           String oldKeyId, String newKeyId,
                           long expectedRevision, long targetRevision,
                           RotationState state, java.time.Instant createdAt,
                           java.time.Instant updatedAt) {
        boolean matches(RotationRequest request) {
            return did.equals(request.did()) && actorId.equals(request.actorId())
                    && oldKeyId.equals(request.oldKeyId()) && newKeyId.equals(request.newKeyId())
                    && expectedRevision == request.expectedRevision()
                    && targetRevision == request.targetRevision();
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
