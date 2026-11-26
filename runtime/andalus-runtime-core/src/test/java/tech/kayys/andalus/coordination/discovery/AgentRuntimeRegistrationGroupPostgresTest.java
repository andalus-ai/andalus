package tech.kayys.andalus.coordination.discovery;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tech.kayys.andalus.agent.capability.AgentRuntimeLease;
import tech.kayys.andalus.agent.capability.AgentRuntimeStatus;
import tech.kayys.andalus.agent.registry.jdbc.JdbcAgentRuntimeRegistrationStore;

/**
 * End-to-end multi-agent recovery test using a real PostgreSQL registration store.
 * It deliberately expires one agent through PostgreSQL's clock while leaving
 * the second agent untouched.
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentRuntimeRegistrationGroupPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private JdbcAgentRuntimeRegistrationStore store;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS andalus_agent_runtime_registration (
                        tenant_id VARCHAR(200) NOT NULL,
                        agent_id VARCHAR(200) NOT NULL,
                        instance_id VARCHAR(200) NOT NULL,
                        owner_id VARCHAR(200) NOT NULL,
                        generation BIGINT NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_andalus_agent_runtime_registration
                            PRIMARY KEY (tenant_id, agent_id, instance_id),
                        CONSTRAINT ck_andalus_agent_runtime_generation CHECK (generation > 0),
                        CONSTRAINT ck_andalus_agent_runtime_status
                            CHECK (status IN ('STARTING', 'AVAILABLE', 'UNAVAILABLE', 'REVOKED'))
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_andalus_agent_runtime_active
                    ON andalus_agent_runtime_registration (tenant_id, agent_id, status, expires_at)
                    """);
            statement.execute("DELETE FROM andalus_agent_runtime_registration");
        }
        store = new JdbcAgentRuntimeRegistrationStore(dataSource);
    }

    @Test
    void oneAgentRecoversWithNewGenerationWithoutDisturbingItsSibling() throws Exception {
        AgentRuntimeRegistrationLifecycle lifecycle =
                new AgentRuntimeRegistrationLifecycle(store, Duration.ofSeconds(30));
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
        AgentRuntimeRegistrationGroup group =
                new AgentRuntimeRegistrationGroup(lifecycle, scheduler);

        try {
            AgentRuntimeRegistrationGroup.Key research = group.register(registration("research"));
            AgentRuntimeRegistrationGroup.Key planner = group.register(registration("planner"));

            assertTrue(group.markAvailable(research));
            assertTrue(group.markAvailable(planner));
            assertTrue(group.isReady());

            AgentRuntimeLease researchBefore = active("research");
            AgentRuntimeLease plannerBefore = active("planner");
            assertEquals(AgentRuntimeStatus.AVAILABLE, researchBefore.status());
            assertEquals(AgentRuntimeStatus.AVAILABLE, plannerBefore.status());

            // Expire only research using the authoritative PostgreSQL clock.
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        UPDATE andalus_agent_runtime_registration
                           SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                         WHERE tenant_id = 'tenant-a'
                           AND agent_id = 'research'
                           AND instance_id = 'instance-a'
                        """);
            }

            AgentRuntimeLease recovered = awaitRecovered("research", researchBefore.token().generation(),
                    Duration.ofSeconds(15));
            assertTrue(recovered.token().generation() > researchBefore.token().generation());
            assertEquals(AgentRuntimeStatus.AVAILABLE, recovered.status());

            // The unaffected registration must retain its original generation and status.
            AgentRuntimeLease plannerAfter = active("planner");
            assertEquals(plannerBefore.token(), plannerAfter.token());
            assertEquals(AgentRuntimeStatus.AVAILABLE, plannerAfter.status());
            assertEquals(AgentRuntimeRegistrationReadiness.State.READY, group.states().get(planner));
            assertEquals(AgentRuntimeRegistrationReadiness.State.READY, group.states().get(research));
            assertTrue(group.isReady());
            assertEquals(2, group.size());
        } finally {
            group.close();
            scheduler.shutdown();
            assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS),
                    "host scheduler should terminate after group shutdown");
        }
    }

    @Test
    void independentRuntimeInstancesFenceExpiredOwnerAndPreserveSuccessorOnShutdown() throws Exception {
        PGSimpleDataSource secondDataSource = new PGSimpleDataSource();
        secondDataSource.setURL(POSTGRES.getJdbcUrl());
        secondDataSource.setUser(POSTGRES.getUsername());
        secondDataSource.setPassword(POSTGRES.getPassword());
        JdbcAgentRuntimeRegistrationStore secondStore =
                new JdbcAgentRuntimeRegistrationStore(secondDataSource);

        ScheduledExecutorService firstScheduler = Executors.newScheduledThreadPool(2);
        ScheduledExecutorService secondScheduler = Executors.newScheduledThreadPool(2);
        AgentRuntimeRegistrationGroup firstGroup = new AgentRuntimeRegistrationGroup(
                new AgentRuntimeRegistrationLifecycle(store, Duration.ofSeconds(30)), firstScheduler);
        AgentRuntimeRegistrationGroup secondGroup = new AgentRuntimeRegistrationGroup(
                new AgentRuntimeRegistrationLifecycle(secondStore, Duration.ofSeconds(30)), secondScheduler);

        AgentRuntimeRegistrationHost.Registration firstRegistration =
                new AgentRuntimeRegistrationHost.Registration(
                        "tenant-a", "checkout", "instance-a", "owner-old",
                        Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1));
        AgentRuntimeRegistrationHost.Registration successorRegistration =
                new AgentRuntimeRegistrationHost.Registration(
                        "tenant-a", "checkout", "instance-a", "owner-new",
                        Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1));
        try {
            AgentRuntimeRegistrationGroup.Key oldKey = firstGroup.register(firstRegistration);
            assertTrue(firstGroup.markAvailable(oldKey));
            AgentRuntimeLease oldLease = active("checkout");

            // Force expiry in PostgreSQL, then let a separate runtime/store take over.
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        UPDATE andalus_agent_runtime_registration
                           SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                         WHERE tenant_id = 'tenant-a'
                           AND agent_id = 'checkout'
                           AND instance_id = 'instance-a'
                        """);
            }

            AgentRuntimeRegistrationGroup.Key newKey = secondGroup.register(successorRegistration);
            assertTrue(secondGroup.markAvailable(newKey));
            AgentRuntimeLease successor = secondStore.findActive("tenant-a", "checkout").getFirst();

            assertTrue(successor.token().generation() > oldLease.token().generation());
            assertEquals("owner-new", successor.token().ownerId());
            assertEquals(AgentRuntimeStatus.AVAILABLE, successor.status());

            // Closing the stale runtime must not revoke the successor's generation.
            firstGroup.close();
            AgentRuntimeLease stillOwned = secondStore.findActive("tenant-a", "checkout").getFirst();
            assertEquals(successor.token(), stillOwned.token());
            assertEquals(AgentRuntimeStatus.AVAILABLE, stillOwned.status());
            assertTrue(secondGroup.isReady());
            assertEquals(1, secondGroup.diagnostics().ready());
        } finally {
            firstGroup.close();
            secondGroup.close();
            firstScheduler.shutdown();
            secondScheduler.shutdown();
            assertTrue(firstScheduler.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(secondScheduler.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void tenantAndAgentRegistrationsRemainDistinctInOneGroup() {
        AgentRuntimeRegistrationLifecycle lifecycle =
                new AgentRuntimeRegistrationLifecycle(store, Duration.ofSeconds(30));
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        AgentRuntimeRegistrationGroup group =
                new AgentRuntimeRegistrationGroup(lifecycle, scheduler);
        try {
            AgentRuntimeRegistrationGroup.Key tenantA = group.register(registration("catalog"));
            AgentRuntimeRegistrationGroup.Key tenantB = group.register(
                    new AgentRuntimeRegistrationHost.Registration(
                            "tenant-b", "catalog", "instance-a", "owner-tenant-b",
                            Duration.ofMillis(200), Duration.ofMillis(100), Duration.ofSeconds(1)));

            assertNotEquals(tenantA, tenantB);
            assertTrue(group.markAvailable(tenantA));
            assertTrue(group.markAvailable(tenantB));
            assertTrue(group.isReady());
            assertEquals(1, store.findActive("tenant-a", "catalog").size());
            assertEquals(1, store.findActive("tenant-b", "catalog").size());
        } finally {
            group.close();
            scheduler.shutdownNow();
        }
    }

    private AgentRuntimeLease active(String agentId) {
        List<AgentRuntimeLease> leases = store.findActive("tenant-a", agentId);
        assertEquals(1, leases.size(), "expected exactly one active registration for " + agentId);
        return leases.getFirst();
    }

    private AgentRuntimeLease awaitRecovered(
            String agentId, long previousGeneration, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            List<AgentRuntimeLease> leases = store.findActive("tenant-a", agentId);
            if (!leases.isEmpty()) {
                AgentRuntimeLease current = leases.getFirst();
                if (current.token().generation() > previousGeneration
                        && current.status() == AgentRuntimeStatus.AVAILABLE) {
                    return current;
                }
            }
            Thread.sleep(50);
        }
        fail("agent " + agentId + " did not recover with a new generation within " + timeout);
        throw new AssertionError("unreachable");
    }

    private static AgentRuntimeRegistrationHost.Registration registration(String agentId) {
        return new AgentRuntimeRegistrationHost.Registration(
                "tenant-a", agentId, "instance-a", "owner-" + agentId,
                Duration.ofMillis(200), Duration.ofMillis(100), Duration.ofSeconds(1));
    }
}
