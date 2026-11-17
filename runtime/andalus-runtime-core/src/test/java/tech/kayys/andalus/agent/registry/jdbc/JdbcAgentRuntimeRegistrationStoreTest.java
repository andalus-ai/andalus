package tech.kayys.andalus.agent.registry.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.agent.capability.AgentRuntimeLease;
import tech.kayys.andalus.agent.capability.AgentRuntimeRegistrationRequest;
import tech.kayys.andalus.agent.capability.AgentRuntimeStatus;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class JdbcAgentRuntimeRegistrationStoreTest {

    private JdbcDataSource dataSource;
    private JdbcAgentRuntimeRegistrationStore store;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:agent-registry;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
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
                        PRIMARY KEY (tenant_id, agent_id, instance_id),
                        CHECK (generation > 0),
                        CHECK (status IN ('STARTING', 'AVAILABLE', 'UNAVAILABLE', 'REVOKED'))
                    )
                    """);
            statement.execute("DELETE FROM andalus_agent_runtime_registration");
        }
        store = new JdbcAgentRuntimeRegistrationStore(dataSource);
    }

    @Test
    void acquiresPublishesRenewsAndRevokesExactOwner() {
        AgentRuntimeLease acquired = store.acquire(request("tenant-a", "node-a", Duration.ofSeconds(30)))
                .orElseThrow();
        assertEquals(AgentRuntimeStatus.STARTING, acquired.status());

        AgentRuntimeLease available = store.publishStatus(
                acquired.token(), AgentRuntimeStatus.AVAILABLE, Duration.ofSeconds(30)).orElseThrow();
        assertEquals(AgentRuntimeStatus.AVAILABLE, available.status());

        AgentRuntimeLease renewed = store.renew(acquired.token(), Duration.ofSeconds(45)).orElseThrow();
        assertTrue(renewed.expiresAt().isAfter(available.expiresAt()));
        assertEquals(1, store.findActive("tenant-a", "agent-a").size());

        assertTrue(store.revoke(acquired.token()));
        assertTrue(store.findActive("tenant-a", "agent-a").isEmpty());
        assertFalse(store.revoke(acquired.token()));
    }

    @Test
    void expiredOwnerCanBeTakenOverAndOldGenerationIsFenced() throws Exception {
        AgentRuntimeLease old = store.acquire(request("tenant-a", "node-a", Duration.ofMillis(2)))
                .orElseThrow();
        Thread.sleep(30);

        AgentRuntimeLease successor = store.acquire(request("tenant-a", "node-b", Duration.ofSeconds(30)))
                .orElseThrow();
        assertEquals(old.token().generation() + 1, successor.token().generation());
        assertNotEquals(old.token().ownerId(), successor.token().ownerId());
        assertTrue(store.renew(old.token(), Duration.ofSeconds(30)).isEmpty());
        assertFalse(store.revoke(old.token()));
        assertEquals("node-b", store.findActive("tenant-a", "agent-a").getFirst().token().ownerId());
    }

    @Test
    void registrationsAreTenantScoped() {
        store.acquire(request("tenant-a", "node-a", Duration.ofSeconds(30))).orElseThrow();
        store.acquire(new AgentRuntimeRegistrationRequest(
                "tenant-b", "agent-a", "instance-a", "node-b", Duration.ofSeconds(30))).orElseThrow();

        assertEquals(1, store.findActive("tenant-a", "agent-a").size());
        assertEquals(1, store.findActive("tenant-b", "agent-a").size());
        assertTrue(store.findActive("tenant-c", "agent-a").isEmpty());
    }

    @Test
    void concurrentAcquisitionHasExactlyOneWinner() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> acquireAfterBarrier("node-a", ready, start));
            var second = executor.submit(() -> acquireAfterBarrier("node-b", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<AgentRuntimeLease> winners = java.util.stream.Stream.of(first.get(), second.get())
                    .flatMap(java.util.Optional::stream)
                    .toList();
            assertEquals(1, winners.size());
            assertEquals(1, store.findActive("tenant-a", "agent-a").size());
        }
    }

    private java.util.Optional<AgentRuntimeLease> acquireAfterBarrier(
            String owner, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("start barrier timed out");
        }
        return store.acquire(request("tenant-a", owner, Duration.ofSeconds(30)));
    }

    private static AgentRuntimeRegistrationRequest request(
            String tenant, String owner, Duration duration) {
        return new AgentRuntimeRegistrationRequest(tenant, "agent-a", "instance-a", owner, duration);
    }
}
