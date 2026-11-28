package tech.kayys.andalus.runtime.identity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Append-only, secret-free audit history for durable ANP key rotation operations. */
@ApplicationScoped
public class PostgresAnpKeyRotationAuditLog {
    private final DataSource dataSource;

    @Inject
    public PostgresAnpKeyRotationAuditLog(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    static void append(Connection connection, String operationId, String eventType,
                       String actorId, String workerId, String fromState,
                       String toState, String failureType) throws SQLException {
        String sql = """
                INSERT INTO andalus_anp_key_rotation_audit
                    (operation_id, event_type, actor_id, worker_id, from_state, to_state, failure_type)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, operationId);
            statement.setString(2, eventType);
            statement.setString(3, actorId);
            statement.setString(4, workerId);
            statement.setString(5, fromState);
            statement.setString(6, toState);
            statement.setString(7, failureType);
            statement.executeUpdate();
        }
    }

    /** Returns the newest audit events first; bounded to protect operational queries. */
    public List<AuditEvent> history(String operationId, int limit) {
        if (operationId == null || operationId.isBlank()) throw new IllegalArgumentException("operationId must not be blank");
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500");
        String sql = """
                SELECT event_id, operation_id, event_type, actor_id, worker_id,
                       from_state, to_state, failure_type, occurred_at
                FROM andalus_anp_key_rotation_audit
                WHERE operation_id = ?
                ORDER BY event_id DESC LIMIT ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, operationId);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<AuditEvent> events = new ArrayList<>();
                while (rows.next()) {
                    events.add(new AuditEvent(rows.getLong("event_id"), rows.getString("operation_id"),
                            rows.getString("event_type"), rows.getString("actor_id"),
                            rows.getString("worker_id"), rows.getString("from_state"),
                            rows.getString("to_state"), rows.getString("failure_type"),
                            rows.getTimestamp("occurred_at").toInstant()));
                }
                return List.copyOf(events);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read ANP key rotation audit history", exception);
        }
    }

    public record AuditEvent(long eventId, String operationId, String eventType,
                             String actorId, String workerId, String fromState,
                             String toState, String failureType, Instant occurredAt) { }
}
