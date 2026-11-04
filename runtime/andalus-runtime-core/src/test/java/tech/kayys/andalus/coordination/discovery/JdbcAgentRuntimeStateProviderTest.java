package tech.kayys.andalus.coordination.discovery;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.coordination.CoordinationContext;

class JdbcAgentRuntimeStateProviderTest {
    private JdbcAgentRuntimeStateProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:agent-state-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        provider = new JdbcAgentRuntimeStateProvider(ds);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE andalus_agent_runtime_state (tenant_id VARCHAR(255) NOT NULL,"
                    + "agent_id VARCHAR(255) NOT NULL,owner_id VARCHAR(255) NOT NULL,generation BIGINT NOT NULL,"
                    + "available BOOLEAN NOT NULL,priority INTEGER NOT NULL,cost_factor DOUBLE PRECISION NOT NULL,"
                    + "expires_at TIMESTAMP WITH TIME ZONE NOT NULL,PRIMARY KEY(tenant_id,agent_id))");
        }
    }

    @Test
    void publishesTenantScopedStateAndUsesFencing() {
        AgentRuntimeState first = new AgentRuntimeState(false, 1, 1.0);
        AgentRuntimeState successor = new AgentRuntimeState(true, 7, 0.5);
        assertTrue(provider.publish(lease("tenant-a", "agent-a", "host-a", 4, first)));
        assertTrue(provider.publish(lease("tenant-a", "agent-a", "host-b", 5, successor)));
        assertFalse(provider.publish(lease("tenant-a", "agent-a", "host-a", 4, first)));
        assertFalse(provider.revoke("tenant-a", "agent-a", "host-a", 4));
        assertEquals(successor, provider.resolve("agent-a", null,
                CoordinationContext.of("tenant-a", "user")).orElseThrow());
        assertTrue(provider.resolve("agent-a", null,
                CoordinationContext.of("tenant-b", "user")).isEmpty());
        assertTrue(provider.revoke("tenant-a", "agent-a", "host-b", 5));
    }

    @Test
    void sameGenerationCanOnlyRenewUnchangedStateAndOwner() {
        AgentRuntimeState state = new AgentRuntimeState(true, 2, 1.0);
        assertTrue(provider.publish(lease("tenant-a", "agent-a", "host-a", 7, state)));
        assertFalse(provider.publish(lease("tenant-a", "agent-a", "host-b", 7, state)));
        assertFalse(provider.publish(lease("tenant-a", "agent-a", "host-a", 7,
                new AgentRuntimeState(false, 2, 1.0))));
        assertTrue(provider.publish(lease("tenant-a", "agent-a", "host-a", 7, state)));
    }

    @Test
    void stopGenerationCannotRevokeNewerOwner() {
        assertTrue(provider.publish(lease("tenant-a", "agent-a", "host-a", 10,
                new AgentRuntimeState(true, 1, 1.0))));
        assertTrue(provider.publish(lease("tenant-a", "agent-a", "host-b", 11,
                new AgentRuntimeState(true, 2, 1.0))));
        assertFalse(provider.revokeAtOrBeforeGeneration("tenant-a", "agent-a", "host-a", 10));
        assertTrue(provider.revokeAtOrBeforeGeneration("tenant-a", "agent-a", "host-b", 11));
    }

    private static AgentRuntimeStateLease lease(String tenant, String agent, String owner,
            long generation, AgentRuntimeState state) {
        return new AgentRuntimeStateLease(agent, tenant, state,
                Instant.now().plusSeconds(60), owner, generation);
    }
}
