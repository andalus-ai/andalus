package tech.kayys.andalus.agent.registry.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tech.kayys.andalus.agent.capability.AgentRuntimeLease;
import tech.kayys.andalus.agent.capability.AgentRuntimeRegistrationRequest;
import tech.kayys.andalus.agent.capability.AgentRuntimeStatus;

/**
 * PostgreSQL concurrency and fencing tests. Two store instances use separate
 * DataSource objects and connections while sharing only the database.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcAgentRuntimeRegistrationPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcAgentRuntimeRegistrationStore runtimeA;
    private JdbcAgentRuntimeRegistrationStore runtimeB;
    private PGSimpleDataSource adminDataSource;

    @BeforeEach
    void setUp() throws Exception {
        adminDataSource = dataSource();
        try (Connection connection = adminDataSource.getConnection();
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

        // Deliberately construct independent DataSource/store objects to model two runtime processes.
        runtimeA = new JdbcAgentRuntimeRegistrationStore(dataSource());
        runtimeB = new JdbcAgentRuntimeRegistrationStore(dataSource());
    }

    @Test
    void independentRuntimeInstancesObserveHeartbeatAndFenceExpiredOwner() throws Exception {
        AgentRuntimeLease ownerA = runtimeA.acquire(request("runtime-a", Duration.ofMinutes(2)))
                .orElseThrow();

        AgentRuntimeLease visibleToB = runtimeB.findActive("tenant-a", "agent-a").getFirst();
        assertEquals(ownerA.token(), visibleToB.token());
        assertEquals(AgentRuntimeStatus.STARTING, visibleToB.status());

        AgentRuntimeLease available = runtimeA.publishStatus(
                ownerA.token(), AgentRuntimeStatus.AVAILABLE, Duration.ofMinutes(2)).orElseThrow();
        assertEquals(AgentRuntimeStatus.AVAILABLE,
                runtimeB.findActive("tenant-a", "agent-a").getFirst().status());

        AgentRuntimeLease renewedByB = runtimeB.renew(ownerA.token(), Duration.ofMinutes(3)).orElseThrow();
        assertTrue(renewedByB.expiresAt().isAfter(available.expiresAt()));

        // Use the database clock rather than sleeping or relying on either JVM's clock.
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE andalus_agent_runtime_registration
                       SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                     WHERE tenant_id = 'tenant-a' AND agent_id = 'agent-a' AND instance_id = 'instance-a'
                    """);
        }

        AgentRuntimeLease ownerB = runtimeB.acquire(request("runtime-b", Duration.ofMinutes(2)))
                .orElseThrow();
        assertEquals(ownerA.token().generation() + 1, ownerB.token().generation());
        assertNotEquals(ownerA.token().ownerId(), ownerB.token().ownerId());

        assertTrue(runtimeA.renew(ownerA.token(), Duration.ofMinutes(2)).isEmpty());
        assertTrue(runtimeA.publishStatus(ownerA.token(), AgentRuntimeStatus.AVAILABLE,
                Duration.ofMinutes(2)).isEmpty());
        assertFalse(runtimeA.revoke(ownerA.token()));

        List<AgentRuntimeLease> active = runtimeA.findActive("tenant-a", "agent-a");
        assertEquals(1, active.size());
        assertEquals(ownerB.token(), active.getFirst().token());
        assertEquals(AgentRuntimeStatus.STARTING, active.getFirst().status());
    }

    @Test
    void concurrentAcquisitionAcrossIndependentStoresHasExactlyOneWinner() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> acquireAfterBarrier(runtimeA, "runtime-a", ready, start));
            var second = executor.submit(() -> acquireAfterBarrier(runtimeB, "runtime-b", ready, start));

            assertTrue(ready.await(10, TimeUnit.SECONDS), "both runtime workers should be ready");
            start.countDown();

            List<AgentRuntimeLease> winners = Stream.of(first.get(20, TimeUnit.SECONDS),
                            second.get(20, TimeUnit.SECONDS))
                    .flatMap(Optional::stream)
                    .toList();
            assertEquals(1, winners.size(), "one process must own the registration");
            assertEquals(1, runtimeA.findActive("tenant-a", "agent-a").size());
            assertEquals(winners.getFirst().token(),
                    runtimeB.findActive("tenant-a", "agent-a").getFirst().token());
        }
    }

    private static Optional<AgentRuntimeLease> acquireAfterBarrier(
            JdbcAgentRuntimeRegistrationStore store,
            String owner,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent acquisition start barrier timed out");
        }
        return store.acquire(request(owner, Duration.ofMinutes(2)));
    }

    private static AgentRuntimeRegistrationRequest request(String owner, Duration ttl) {
        return new AgentRuntimeRegistrationRequest(
                "tenant-a", "agent-a", "instance-a", owner, ttl);
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
