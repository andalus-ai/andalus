package tech.kayys.andalus.coordination.discovery;

import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.andalus.coordination.CoordinationContext;
import tech.kayys.andalus.coordination.CoordinationRequest;

/** Shared tenant-scoped JDBC lease store for agent runtime state. */
@ApplicationScoped
public class JdbcAgentRuntimeStateProvider implements AgentRuntimeStateProvider {
    private final DataSource dataSource;

    @Inject
    public JdbcAgentRuntimeStateProvider(DataSource dataSource) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource);
    }

    public boolean publish(AgentRuntimeStateLease lease) {
        if (lease == null) throw new IllegalArgumentException("lease must not be null");
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                Instant dbNow;
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT CURRENT_TIMESTAMP")) {
                    r.next();
                    dbNow = r.getTimestamp(1).toInstant();
                }
                if (!lease.expiresAt().isAfter(dbNow)) {
                    throw new IllegalArgumentException("lease expiry must be in the future");
                }
                Row current = select(c, lease.tenantId(), lease.agentId());
                boolean accepted;
                if (current == null) accepted = insert(c, lease);
                else if (lease.generation() > current.generation
                        || (lease.generation() == current.generation
                            && lease.ownerId().equals(current.owner)
                            && lease.state().equals(current.state)
                            && lease.expiresAt().isAfter(current.expiry))) {
                    accepted = update(c, lease);
                } else accepted = false;
                c.commit();
                return accepted;
            } catch (SQLException | RuntimeException e) {
                try { c.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                if (e instanceof SQLException sql && isConstraintViolation(sql)) return false;
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Unable to publish agent state lease", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to publish agent state lease", e);
        }
    }

    public boolean revoke(String tenant, String agent, String owner, long generation) {
        validate(tenant, agent, owner);
        if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
        return delete(tenant, agent, owner, generation, "=");
    }

    public boolean revokeAtOrBeforeGeneration(String tenant, String agent, String owner, long generation) {
        validate(tenant, agent, owner);
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        return delete(tenant, agent, owner, generation, "<=");
    }

    @Override
    public Optional<AgentRuntimeState> resolve(String agentId, CoordinationRequest request, CoordinationContext context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        if (agentId == null || agentId.isBlank() || context.tenantId() == null || context.tenantId().isBlank())
            return Optional.empty();
        String sql = "SELECT available, priority, cost_factor FROM andalus_agent_runtime_state "
                + "WHERE tenant_id=? AND agent_id=? AND expires_at>CURRENT_TIMESTAMP";
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, context.tenantId().trim());
            p.setString(2, agentId.trim());
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? Optional.of(new AgentRuntimeState(r.getBoolean(1), r.getInt(2), r.getDouble(3)))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to resolve agent runtime state", e);
        }
    }

    private static Row select(Connection c, String tenant, String agent) throws SQLException {
        String sql = "SELECT owner_id,generation,available,priority,cost_factor,expires_at "
                + "FROM andalus_agent_runtime_state WHERE tenant_id=? AND agent_id=? FOR UPDATE";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, tenant); p.setString(2, agent);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                return new Row(r.getString(1), r.getLong(2),
                        new AgentRuntimeState(r.getBoolean(3), r.getInt(4), r.getDouble(5)),
                        r.getTimestamp(6).toInstant());
            }
        }
    }

    private static boolean insert(Connection c, AgentRuntimeStateLease l) throws SQLException {
        String sql = "INSERT INTO andalus_agent_runtime_state "
                + "(tenant_id,agent_id,owner_id,generation,available,priority,cost_factor,expires_at) "
                + "VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            bind(p, l);
            return p.executeUpdate() == 1;
        }
    }

    private static boolean update(Connection c, AgentRuntimeStateLease l) throws SQLException {
        String sql = "UPDATE andalus_agent_runtime_state SET owner_id=?,generation=?,available=?,priority=?,"
                + "cost_factor=?,expires_at=? WHERE tenant_id=? AND agent_id=?";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, l.ownerId()); p.setLong(2, l.generation());
            p.setBoolean(3, l.state().available()); p.setInt(4, l.state().priority());
            p.setDouble(5, l.state().costFactor()); p.setTimestamp(6, Timestamp.from(l.expiresAt()));
            p.setString(7, l.tenantId()); p.setString(8, l.agentId());
            return p.executeUpdate() == 1;
        }
    }

    private static void bind(PreparedStatement p, AgentRuntimeStateLease l) throws SQLException {
        p.setString(1, l.tenantId()); p.setString(2, l.agentId()); p.setString(3, l.ownerId());
        p.setLong(4, l.generation()); p.setBoolean(5, l.state().available());
        p.setInt(6, l.state().priority()); p.setDouble(7, l.state().costFactor());
        p.setTimestamp(8, Timestamp.from(l.expiresAt()));
    }

    private boolean delete(String tenant, String agent, String owner, long generation, String comparison) {
        String sql = "DELETE FROM andalus_agent_runtime_state WHERE tenant_id=? AND agent_id=? "
                + "AND owner_id=? AND generation " + comparison + " ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, tenant.trim()); p.setString(2, agent.trim());
            p.setString(3, owner.trim()); p.setLong(4, generation);
            return p.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to revoke agent runtime state", e);
        }
    }

    private static boolean isConstraintViolation(SQLException e) {
        for (SQLException x = e; x != null; x = x.getNextException())
            if (x.getSQLState() != null && x.getSQLState().startsWith("23")) return true;
        return false;
    }

    private static void validate(String tenant, String agent, String owner) {
        if (tenant == null || tenant.isBlank() || agent == null || agent.isBlank()
                || owner == null || owner.isBlank())
            throw new IllegalArgumentException("tenantId, agentId and ownerId must not be blank");
    }

    private record Row(String owner, long generation, AgentRuntimeState state, Instant expiry) {}
}
