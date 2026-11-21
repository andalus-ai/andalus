package tech.kayys.andalus.runtime.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class PostgresAnpKeyRotationRecoveryQueueTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private PostgresAnpKeyRotationJournal journal;
    private PostgresAnpKeyRotationRecoveryQueue queue;

    @BeforeEach
    void initialize() throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS andalus_anp_key_rotations");
            statement.execute("""
                    CREATE TABLE andalus_anp_key_rotations (
                        operation_id VARCHAR(128) PRIMARY KEY,
                        did VARCHAR(512) NOT NULL,
                        actor_id VARCHAR(256) NOT NULL,
                        old_key_id VARCHAR(1024) NOT NULL,
                        new_key_id VARCHAR(1024) NOT NULL,
                        expected_revision BIGINT NOT NULL CHECK (expected_revision > 0),
                        target_revision BIGINT NOT NULL CHECK (target_revision > expected_revision),
                        state VARCHAR(32) NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        lease_owner VARCHAR(256),
                        lease_until TIMESTAMPTZ,
                        recovery_attempts BIGINT NOT NULL DEFAULT 0
                    )
                    """);
        }
        journal = new PostgresAnpKeyRotationJournal(dataSource);
        queue = new PostgresAnpKeyRotationRecoveryQueue(dataSource);
    }

    @Test
    void discoversIncompleteOperationsInDeterministicOrderAndExcludesCompleted() {
        journal.findOrPrepare(request("rotation-b"));
        journal.findOrPrepare(request("rotation-a"));
        journal.findOrPrepare(request("rotation-complete"));
        journal.advance("rotation-complete",
                PostgresAnpKeyRotationJournal.RotationState.PREPARED,
                PostgresAnpKeyRotationJournal.RotationState.DID_DOCUMENT_UPDATED);
        journal.advance("rotation-complete",
                PostgresAnpKeyRotationJournal.RotationState.DID_DOCUMENT_UPDATED,
                PostgresAnpKeyRotationJournal.RotationState.NEW_KEY_ACTIVATED);
        journal.advance("rotation-complete",
                PostgresAnpKeyRotationJournal.RotationState.NEW_KEY_ACTIVATED,
                PostgresAnpKeyRotationJournal.RotationState.OLD_KEY_REVOKED);
        journal.advance("rotation-complete",
                PostgresAnpKeyRotationJournal.RotationState.OLD_KEY_REVOKED,
                PostgresAnpKeyRotationJournal.RotationState.COMPLETED);

        List<PostgresAnpKeyRotationJournal.Rotation> pending = queue.findPending(10);

        assertEquals(2, pending.size());
        assertTrue(pending.stream().allMatch(r -> r.state()
                != PostgresAnpKeyRotationJournal.RotationState.COMPLETED));
        assertEquals(2, queue.findPending(2).size());
        assertEquals(1, queue.findPending(1).size());
    }

    @Test
    void rejectsUnboundedOrInvalidRecoveryBatchSizes() {
        assertThrows(IllegalArgumentException.class, () -> queue.findPending(0));
        assertThrows(IllegalArgumentException.class, () -> queue.findPending(501));
    }


    @Test
    void claimsAreExclusiveAndOnlyTheOwningWorkerCanRelease() {
        journal.findOrPrepare(request("rotation-a"));
        journal.findOrPrepare(request("rotation-b"));

        List<PostgresAnpKeyRotationJournal.Rotation> first =
                queue.claimPending("worker-1", Duration.ofMinutes(2), 2);
        List<PostgresAnpKeyRotationJournal.Rotation> second =
                queue.claimPending("worker-2", Duration.ofMinutes(2), 2);

        assertEquals(2, first.size());
        assertTrue(second.isEmpty());
        String operationId = first.get(0).operationId();
        assertFalse(queue.release(operationId, "worker-2"));
        assertTrue(queue.release(operationId, "worker-1"));
        assertEquals(1, queue.claimPending("worker-2", Duration.ofMinutes(2), 2).size());
    }

    @Test
    void rejectsInvalidLeaseDurationsAndWorkerIds() {
        assertThrows(IllegalArgumentException.class,
                () -> queue.claimPending("worker", Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class,
                () -> queue.claimPending(" ", Duration.ofSeconds(5), 1));
    }

    private static PostgresAnpKeyRotationJournal.RotationRequest request(String id) {
        return new PostgresAnpKeyRotationJournal.RotationRequest(
                id, "did:wba:example.com:agent", "security-admin",
                "did:wba:example.com:agent#old", "did:wba:example.com:agent#new-" + id,
                1, 2);
    }
}
