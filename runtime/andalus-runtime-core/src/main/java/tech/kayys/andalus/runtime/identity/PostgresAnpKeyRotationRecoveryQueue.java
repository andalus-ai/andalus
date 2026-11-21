package tech.kayys.andalus.runtime.identity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded recovery scan for incomplete ANP key rotations.
 *
 * <p>This component only discovers durable work. A coordinator must re-read each row,
 * perform idempotent side effects, and use journal compare-and-set transitions. Multiple
 * workers may receive the same item; the journal remains the concurrency authority.
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

    private final DataSource dataSource;

    @Inject
    public PostgresAnpKeyRotationRecoveryQueue(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Returns the oldest incomplete operations, with a strict upper bound on batch size. */
    public List<PostgresAnpKeyRotationJournal.Rotation> findPending(int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Recovery batch limit must be between 1 and 500");
        }
        List<PostgresAnpKeyRotationJournal.Rotation> pending = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(PENDING)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    pending.add(new PostgresAnpKeyRotationJournal.Rotation(
                            rows.getString("operation_id"), rows.getString("did"),
                            rows.getString("actor_id"), rows.getString("old_key_id"),
                            rows.getString("new_key_id"), rows.getLong("expected_revision"),
                            rows.getLong("target_revision"),
                            PostgresAnpKeyRotationJournal.RotationState.valueOf(rows.getString("state")),
                            rows.getTimestamp("created_at").toInstant(),
                            rows.getTimestamp("updated_at").toInstant()));
                }
            }
            return List.copyOf(pending);
        } catch (SQLException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to scan pending ANP key rotations", exception);
        }
    }
}
