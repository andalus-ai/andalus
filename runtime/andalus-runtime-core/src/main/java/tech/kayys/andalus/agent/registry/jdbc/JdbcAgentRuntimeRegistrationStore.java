package tech.kayys.andalus.agent.registry.jdbc;

import tech.kayys.andalus.agent.capability.AgentRuntimeLease;
import tech.kayys.andalus.agent.capability.AgentRuntimeLeaseToken;
import tech.kayys.andalus.agent.capability.AgentRuntimeRegistrationRequest;
import tech.kayys.andalus.agent.capability.AgentRuntimeRegistrationStore;
import tech.kayys.andalus.agent.capability.AgentRuntimeStatus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;

/**
 * JDBC-backed, database-clock-authoritative agent runtime registration store.
 *
 * <p>Requires the schema in
 * {@code META-INF/andalus/agent-runtime-registration-schema.sql} to be applied
 * before use. The table's primary key serializes ownership per tenant/agent/
 * instance; row locks protect renewals and state changes.</p>
 *
 * <p>This class deliberately does not own a connection pool or schema
 * migration lifecycle. The application supplies its configured DataSource.</p>
 */
@ApplicationScoped
public final class JdbcAgentRuntimeRegistrationStore implements AgentRuntimeRegistrationStore {

    private static final String SELECT_LOCKED = """
            SELECT owner_id, generation, status, expires_at
              FROM andalus_agent_runtime_registration
             WHERE tenant_id = ? AND agent_id = ? AND instance_id = ?
             FOR UPDATE
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcAgentRuntimeRegistrationStore(DataSource dataSource) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Optional<AgentRuntimeLease> acquire(AgentRuntimeRegistrationRequest request) {
        java.util.Objects.requireNonNull(request, "request must not be null");
        return inTransaction(connection -> {
            Optional<Row> existing = lock(connection, request.tenantId(), request.agentId(), request.instanceId());
            if (existing.isEmpty()) {
                Savepoint savepoint = connection.setSavepoint();
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO andalus_agent_runtime_registration
                            (tenant_id, agent_id, instance_id, owner_id, generation, status, expires_at)
                        VALUES (?, ?, ?, ?, 1, ?, ?)
                        """)) {
                    Instant now = databaseNow(connection);
                    insert.setString(1, request.tenantId());
                    insert.setString(2, request.agentId());
                    insert.setString(3, request.instanceId());
                    insert.setString(4, request.ownerId());
                    insert.setString(5, AgentRuntimeStatus.STARTING.name());
                    setInstant(insert, 6, now.plus(request.leaseDuration()));
                    try {
                        insert.executeUpdate();
                        connection.releaseSavepoint(savepoint);
                        return Optional.of(lease(request, 1, AgentRuntimeStatus.STARTING,
                                now.plus(request.leaseDuration())));
                    } catch (SQLException insertFailure) {
                        connection.rollback(savepoint);
                        Optional<Row> raced = lock(connection, request.tenantId(),
                                request.agentId(), request.instanceId());
                        if (raced.isEmpty()) {
                            throw insertFailure;
                        }
                        existing = raced;
                    }
                }
            }

            Row row = existing.orElseThrow();
            Instant now = databaseNow(connection);
            if (!AgentRuntimeStatus.REVOKED.name().equals(row.status()) && row.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            long generation = Math.addExact(row.generation(), 1);
            Instant expiresAt = now.plus(request.leaseDuration());
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE andalus_agent_runtime_registration
                       SET owner_id = ?, generation = ?, status = ?, expires_at = ?
                     WHERE tenant_id = ? AND agent_id = ? AND instance_id = ?
                    """)) {
                update.setString(1, request.ownerId());
                update.setLong(2, generation);
                update.setString(3, AgentRuntimeStatus.STARTING.name());
                setInstant(update, 4, expiresAt);
                update.setString(5, request.tenantId());
                update.setString(6, request.agentId());
                update.setString(7, request.instanceId());
                update.executeUpdate();
            }
            return Optional.of(lease(request, generation, AgentRuntimeStatus.STARTING, expiresAt));
        });
    }

    @Override
    public Optional<AgentRuntimeLease> renew(AgentRuntimeLeaseToken token, Duration leaseDuration) {
        validateDuration(leaseDuration);
        java.util.Objects.requireNonNull(token, "token must not be null");
        return inTransaction(connection -> {
            Optional<Row> current = lock(connection, token.tenantId(), token.agentId(), token.instanceId());
            if (!owns(current, token) || !current.orElseThrow().expiresAt().isAfter(databaseNow(connection))) {
                return Optional.empty();
            }
            Instant expiresAt = databaseNow(connection).plus(leaseDuration);
            updateLease(connection, token, current.orElseThrow().status(), expiresAt);
            return Optional.of(new AgentRuntimeLease(token, status(current.orElseThrow().status()), expiresAt));
        });
    }

    @Override
    public Optional<AgentRuntimeLease> publishStatus(
            AgentRuntimeLeaseToken token, AgentRuntimeStatus status, Duration leaseDuration) {
        java.util.Objects.requireNonNull(token, "token must not be null");
        java.util.Objects.requireNonNull(status, "status must not be null");
        validateDuration(leaseDuration);
        if (status == AgentRuntimeStatus.REVOKED) {
            throw new IllegalArgumentException("use revoke() to publish REVOKED");
        }
        return inTransaction(connection -> {
            Optional<Row> current = lock(connection, token.tenantId(), token.agentId(), token.instanceId());
            if (!owns(current, token) || !current.orElseThrow().expiresAt().isAfter(databaseNow(connection))) {
                return Optional.empty();
            }
            Instant expiresAt = databaseNow(connection).plus(leaseDuration);
            updateLease(connection, token, status.name(), expiresAt);
            return Optional.of(new AgentRuntimeLease(token, status, expiresAt));
        });
    }

    @Override
    public boolean revoke(AgentRuntimeLeaseToken token) {
        java.util.Objects.requireNonNull(token, "token must not be null");
        return inTransaction(connection -> {
            Optional<Row> current = lock(connection, token.tenantId(), token.agentId(), token.instanceId());
            if (!owns(current, token)) {
                return false;
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE andalus_agent_runtime_registration
                       SET status = ?
                     WHERE tenant_id = ? AND agent_id = ? AND instance_id = ?
                       AND owner_id = ? AND generation = ?
                    """)) {
                update.setString(1, AgentRuntimeStatus.REVOKED.name());
                bindToken(update, token, 2);
                return update.executeUpdate() == 1;
            }
        });
    }

    @Override
    public List<AgentRuntimeLease> findActive(String tenantId, String agentId) {
        String tenant = requireText(tenantId, "tenantId");
        String agent = requireText(agentId, "agentId");
        return inTransaction(connection -> {
            Instant now = databaseNow(connection);
            List<AgentRuntimeLease> leases = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT instance_id, owner_id, generation, status, expires_at
                      FROM andalus_agent_runtime_registration
                     WHERE tenant_id = ? AND agent_id = ? AND status <> ?
                       AND expires_at > ?
                     ORDER BY instance_id
                    """)) {
                query.setString(1, tenant);
                query.setString(2, agent);
                query.setString(3, AgentRuntimeStatus.REVOKED.name());
                setInstant(query, 4, now);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        AgentRuntimeLeaseToken token = new AgentRuntimeLeaseToken(
                                tenant, agent, rows.getString("instance_id"),
                                rows.getString("owner_id"), rows.getLong("generation"));
                        leases.add(new AgentRuntimeLease(token, status(rows.getString("status")),
                                instant(rows, "expires_at")));
                    }
                }
            }
            return List.copyOf(leases);
        });
    }

    private Optional<Row> lock(Connection connection, String tenant, String agent, String instance)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(SELECT_LOCKED)) {
            query.setString(1, tenant);
            query.setString(2, agent);
            query.setString(3, instance);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Row(rows.getString("owner_id"), rows.getLong("generation"),
                        rows.getString("status"), instant(rows, "expires_at")));
            }
        }
    }

    private void updateLease(Connection connection, AgentRuntimeLeaseToken token,
                             String status, Instant expiresAt) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE andalus_agent_runtime_registration
                   SET status = ?, expires_at = ?
                 WHERE tenant_id = ? AND agent_id = ? AND instance_id = ?
                   AND owner_id = ? AND generation = ?
                """)) {
            update.setString(1, status);
            setInstant(update, 2, expiresAt);
            bindToken(update, token, 3);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("registration ownership changed while row was locked");
            }
        }
    }

    private static void bindToken(PreparedStatement statement, AgentRuntimeLeaseToken token, int start)
            throws SQLException {
        statement.setString(start, token.tenantId());
        statement.setString(start + 1, token.agentId());
        statement.setString(start + 2, token.instanceId());
        statement.setString(start + 3, token.ownerId());
        statement.setLong(start + 4, token.generation());
    }

    private static boolean owns(Optional<Row> row, AgentRuntimeLeaseToken token) {
        return row.isPresent()
                && token.ownerId().equals(row.get().ownerId())
                && token.generation() == row.get().generation()
                && !AgentRuntimeStatus.REVOKED.name().equals(row.get().status());
    }

    private static Instant databaseNow(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT CURRENT_TIMESTAMP");
             ResultSet row = query.executeQuery()) {
            row.next();
            return row.getTimestamp(1, utcCalendar()).toInstant();
        }
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp timestamp = rows.getTimestamp(column, utcCalendar());
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void setInstant(PreparedStatement statement, int index, Instant instant) throws SQLException {
        statement.setTimestamp(index, Timestamp.from(instant), utcCalendar());
    }

    private static java.util.Calendar utcCalendar() {
        return java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private static AgentRuntimeLease lease(AgentRuntimeRegistrationRequest request, long generation,
                                           AgentRuntimeStatus status, Instant expiresAt) {
        return new AgentRuntimeLease(new AgentRuntimeLeaseToken(request.tenantId(), request.agentId(),
                request.instanceId(), request.ownerId(), generation), status, expiresAt);
    }

    private static AgentRuntimeStatus status(String value) {
        return AgentRuntimeStatus.valueOf(value);
    }

    private static void validateDuration(Duration duration) {
        java.util.Objects.requireNonNull(duration, "leaseDuration must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private <T> T inTransaction(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception failure) {
                connection.rollback();
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("agent runtime registration JDBC operation failed", failure);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("agent runtime registration JDBC operation failed", failure);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws Exception;
    }

    private record Row(String ownerId, long generation, String status, Instant expiresAt) { }
}
