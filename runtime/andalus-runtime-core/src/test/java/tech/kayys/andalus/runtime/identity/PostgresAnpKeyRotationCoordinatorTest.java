package tech.kayys.andalus.runtime.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tech.kayys.andalus.anp.identity.AnpIdentityKeyPair;
import tech.kayys.andalus.anp.identity.AnpKeyStore;
import tech.kayys.andalus.anp.identity.DidWbaDocument;
import tech.kayys.andalus.anp.identity.DidWbaVerificationMethod;
import tech.kayys.andalus.anp.identity.TrustedDidDocument;
import tech.kayys.andalus.anp.identity.TrustedDidDocumentStore;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class PostgresAnpKeyRotationCoordinatorTest {
    private static final String DID = "did:wba:example.com:agent";
    private static final String ACTOR = "security-admin";
    private static final String OLD_ID = DID + "#key-old";
    private static final String NEW_ID = DID + "#key-new";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private PostgresAnpKeyRotationJournal journal;
    private PostgresAnpKeyRotationRecoveryQueue queue;
    private TrustedDidDocumentStore didStore;
    private PostgresAnpKeyStore keyStore;
    private TrustedDidDocument initial;
    private TrustedDidDocument candidate;
    private AnpIdentityKeyPair oldPair;
    private AnpIdentityKeyPair newPair;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        createSchema();
        journal = new PostgresAnpKeyRotationJournal(dataSource);
        queue = new PostgresAnpKeyRotationRecoveryQueue(dataSource);
        didStore = new PostgresTrustedDidDocumentStore(dataSource, new ObjectMapper());
        keyStore = new PostgresAnpKeyStore(dataSource, new TestProtector());

        oldPair = generatePair(OLD_ID);
        newPair = generatePair(NEW_ID);
        initial = trustedDocument(oldPair, 1);
        candidate = trustedDocument(newPair, 2);
        didStore.register(initial);
        keyStore.storeKey(DID, oldPair);
    }

    @Test
    void completesRotationAndMakesOldKeyUnavailable() {
        PostgresAnpKeyRotationCoordinator coordinator = coordinator(keyStore, (actor, did) -> { });
        var completed = coordinator.rotate(request("rotation-success"), candidate, newPair);

        assertEquals(PostgresAnpKeyRotationJournal.RotationState.COMPLETED, completed.state());
        assertEquals(candidate, didStore.find(DID).orElseThrow());
        assertTrue(keyStore.getActiveKey(NEW_ID).isPresent());
        assertTrue(keyStore.getActiveKey(OLD_ID).isEmpty());
        assertEquals(NEW_ID, keyStore.getAgentKeyId(DID).orElseThrow());
    }

    @Test
    void resumesAfterDidUpdateWhenNewKeyActivationInitiallyFails() {
        AtomicBoolean failOnce = new AtomicBoolean(true);
        AnpKeyStore interruptedStore = new DelegatingKeyStore(keyStore) {
            @Override
            public void storeKey(String agentId, AnpIdentityKeyPair pair) {
                if (failOnce.compareAndSet(true, false)) {
                    throw new IllegalStateException("simulated process interruption");
                }
                super.storeKey(agentId, pair);
            }
        };
        PostgresAnpKeyRotationCoordinator firstAttempt = coordinator(interruptedStore, (actor, did) -> { });

        assertThrows(IllegalStateException.class,
                () -> firstAttempt.rotate(request("rotation-retry"), candidate, newPair));
        assertEquals(PostgresAnpKeyRotationJournal.RotationState.DID_DOCUMENT_UPDATED,
                journal.find("rotation-retry").orElseThrow().state());
        assertTrue(didStore.find(DID).orElseThrow().document().isAuthenticationMethod("#key-new"));
        assertTrue(keyStore.getActiveKey(OLD_ID).isPresent());

        AnpKeyRotationMaterialProvider provider = ignored ->
                new AnpKeyRotationMaterialProvider.RotationMaterial(candidate, newPair);
        PostgresAnpKeyRotationCoordinator recovery = new PostgresAnpKeyRotationCoordinator(
                journal, didStore, keyStore, (actor, did) -> { }, provider, queue);
        var completed = recovery.resume("rotation-retry");

        assertEquals(PostgresAnpKeyRotationJournal.RotationState.COMPLETED, completed.state());
        assertTrue(keyStore.getActiveKey(NEW_ID).isPresent());
        assertTrue(keyStore.getActiveKey(OLD_ID).isEmpty());
    }

    @Test
    void authorizationFailureDoesNotPrepareOrMutateRotation() {
        PostgresAnpKeyRotationCoordinator coordinator = coordinator(keyStore,
                (actor, did) -> { throw new SecurityException("not authorized"); });

        assertThrows(SecurityException.class,
                () -> coordinator.rotate(request("rotation-denied"), candidate, newPair));
        assertTrue(journal.find("rotation-denied").isEmpty());
        assertEquals(initial, didStore.find(DID).orElseThrow());
        assertTrue(keyStore.getActiveKey(OLD_ID).isPresent());
        assertTrue(keyStore.getActiveKey(NEW_ID).isEmpty());
    }

    @Test
    void foregroundResumeCannotBypassAnExistingRecoveryLease() {
        journal.findOrPrepare(request("rotation-lease-conflict"));
        assertTrue(queue.claimOperation("rotation-lease-conflict", "recovery-owner", Duration.ofMinutes(2)));

        PostgresAnpKeyRotationCoordinator coordinator = coordinator(keyStore, (actor, did) -> { });
        assertThrows(IllegalStateException.class, () -> coordinator.resume("rotation-lease-conflict"));

        assertEquals(PostgresAnpKeyRotationJournal.RotationState.PREPARED,
                journal.find("rotation-lease-conflict").orElseThrow().state());
        assertEquals(initial, didStore.find(DID).orElseThrow());
        assertTrue(keyStore.getActiveKey(OLD_ID).isPresent());
        assertFalse(queue.claimOperation("rotation-lease-conflict", "second-worker", Duration.ofMinutes(2)));
        long generation = queue.leaseGeneration("rotation-lease-conflict", "recovery-owner");
        assertTrue(queue.ownsLease("rotation-lease-conflict", "recovery-owner", generation));
    }

    @Test
    void reclaimedLeaseAdvancesGenerationAndRejectsStaleWorker() throws Exception {
        journal.findOrPrepare(request("rotation-fenced"));
        assertTrue(queue.claimOperation("rotation-fenced", "worker-old", Duration.ofMinutes(1)));
        long oldGeneration = queue.leaseGeneration("rotation-fenced", "worker-old");
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     UPDATE andalus_anp_key_rotations SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                     WHERE operation_id = ?
                     """)) {
            statement.setString(1, "rotation-fenced");
            statement.executeUpdate();
        }

        assertTrue(queue.claimOperation("rotation-fenced", "worker-new", Duration.ofMinutes(1)));
        long newGeneration = queue.leaseGeneration("rotation-fenced", "worker-new");

        assertTrue(newGeneration > oldGeneration);
        assertFalse(queue.renewLease("rotation-fenced", "worker-old", oldGeneration, Duration.ofMinutes(1)));
        assertFalse(queue.ownsLease("rotation-fenced", "worker-old", oldGeneration));
        assertTrue(queue.ownsLease("rotation-fenced", "worker-new", newGeneration));
    }

    @Test
    void staleFenceCannotWriteTrustedDidOrSigningKeys() throws Exception {
        journal.findOrPrepare(request("rotation-atomic-fence"));
        assertTrue(queue.claimOperation("rotation-atomic-fence", "worker-old", Duration.ofMinutes(1)));
        long oldGeneration = queue.leaseGeneration("rotation-atomic-fence", "worker-old");
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     UPDATE andalus_anp_key_rotations SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                     WHERE operation_id = ?
                     """)) {
            statement.setString(1, "rotation-atomic-fence");
            statement.executeUpdate();
        }
        assertTrue(queue.claimOperation("rotation-atomic-fence", "worker-new", Duration.ofMinutes(1)));

        AnpKeyRotationFence stalePrepared = new AnpKeyRotationFence(
                "rotation-atomic-fence", "worker-old", oldGeneration, "PREPARED");
        assertThrows(SecurityException.class, () -> didStore.register(candidate, stalePrepared));
        assertEquals(initial, didStore.find(DID).orElseThrow());
        assertThrows(SecurityException.class, () -> journal.advance("rotation-atomic-fence",
                PostgresAnpKeyRotationJournal.RotationState.PREPARED,
                PostgresAnpKeyRotationJournal.RotationState.DID_DOCUMENT_UPDATED, stalePrepared));

        assertEquals(PostgresAnpKeyRotationJournal.RotationState.PREPARED,
                journal.find("rotation-atomic-fence").orElseThrow().state());
        journal.advance("rotation-atomic-fence",
                PostgresAnpKeyRotationJournal.RotationState.PREPARED,
                PostgresAnpKeyRotationJournal.RotationState.DID_DOCUMENT_UPDATED);
        AnpKeyRotationFence staleActivation = new AnpKeyRotationFence(
                "rotation-atomic-fence", "worker-old", oldGeneration, "DID_DOCUMENT_UPDATED");
        assertThrows(SecurityException.class, () -> keyStore.storeKey(DID, newPair, staleActivation));
        assertTrue(keyStore.getActiveKey(NEW_ID).isEmpty());

        journal.advance("rotation-atomic-fence",
                PostgresAnpKeyRotationJournal.RotationState.DID_DOCUMENT_UPDATED,
                PostgresAnpKeyRotationJournal.RotationState.NEW_KEY_ACTIVATED);
        AnpKeyRotationFence staleRevocation = new AnpKeyRotationFence(
                "rotation-atomic-fence", "worker-old", oldGeneration, "NEW_KEY_ACTIVATED");
        assertThrows(SecurityException.class, () -> keyStore.revokeKey(OLD_ID, staleRevocation));
        assertTrue(keyStore.getActiveKey(OLD_ID).isPresent());
    }

    @Test
    void recoveryWorkerCompletesPreparedOperationUsingProtectedMaterialProvider() {
        journal.findOrPrepare(request("rotation-recovery"));
        AnpKeyRotationMaterialProvider provider = ignored ->
                new AnpKeyRotationMaterialProvider.RotationMaterial(candidate, newPair);
        PostgresAnpKeyRotationCoordinator coordinator = new PostgresAnpKeyRotationCoordinator(
                journal, didStore, keyStore, (actor, did) -> { }, provider, queue);

        var results = coordinator.recoverPending("worker-a", Duration.ofMinutes(1), 10);

        assertEquals(1, results.size());
        assertTrue(results.get(0).completed());
        assertEquals(PostgresAnpKeyRotationJournal.RotationState.COMPLETED,
                journal.find("rotation-recovery").orElseThrow().state());
        assertTrue(keyStore.getActiveKey(OLD_ID).isEmpty());
    }

    private PostgresAnpKeyRotationCoordinator coordinator(AnpKeyStore store,
                                                           AnpKeyRotationAuthorizer authorizer) {
        return new PostgresAnpKeyRotationCoordinator(journal, didStore, store, authorizer,
                ignored -> new AnpKeyRotationMaterialProvider.RotationMaterial(candidate, newPair), queue);
    }

    private static PostgresAnpKeyRotationJournal.RotationRequest request(String id) {
        return new PostgresAnpKeyRotationJournal.RotationRequest(
                id, DID, ACTOR, OLD_ID, NEW_ID, 1, 2);
    }

    private static TrustedDidDocument trustedDocument(AnpIdentityKeyPair pair, long revision) {
        byte[] x509 = pair.publicKeyBytes();
        byte[] raw = java.util.Arrays.copyOfRange(x509, x509.length - 32, x509.length);
        String x = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Map<String, Object> jwk = Map.of("kty", "OKP", "crv", "Ed25519", "x", x);
        DidWbaVerificationMethod method = DidWbaVerificationMethod.jwk(
                pair.keyId(), DID, jwk);
        return new TrustedDidDocument(new DidWbaDocument(
                DID, List.of(method), List.of(pair.keyId()), Map.of()), revision);
    }

    private static AnpIdentityKeyPair generatePair(String keyId) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair pair = generator.generateKeyPair();
        return new AnpIdentityKeyPair(keyId, "Ed25519",
                pair.getPrivate().getEncoded(), pair.getPublic().getEncoded());
    }

    private void createSchema() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("DROP TABLE IF EXISTS andalus_anp_agent_signing_keys");
            sql.execute("DROP TABLE IF EXISTS andalus_anp_revoked_key_ids");
            sql.execute("DROP TABLE IF EXISTS andalus_anp_signing_keys");
            sql.execute("DROP TABLE IF EXISTS andalus_anp_key_rotations");
            sql.execute("DROP TABLE IF EXISTS andalus_trusted_did_documents");
            sql.execute("""
                    CREATE TABLE andalus_trusted_did_documents (
                        did VARCHAR(512) PRIMARY KEY, revision BIGINT NOT NULL,
                        document_json JSONB NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            sql.execute("""
                    CREATE TABLE andalus_anp_key_rotations (
                        operation_id VARCHAR(128) PRIMARY KEY, did VARCHAR(512) NOT NULL,
                        actor_id VARCHAR(256) NOT NULL, old_key_id VARCHAR(1024) NOT NULL,
                        new_key_id VARCHAR(1024) NOT NULL, expected_revision BIGINT NOT NULL,
                        target_revision BIGINT NOT NULL, state VARCHAR(32) NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        lease_owner VARCHAR(256), lease_until TIMESTAMPTZ,
                        recovery_attempts BIGINT NOT NULL DEFAULT 0,
                        lease_generation BIGINT NOT NULL DEFAULT 0 CHECK (lease_generation >= 0))
                    """);
            sql.execute("""
                    CREATE TABLE andalus_anp_signing_keys (
                        key_id VARCHAR(512) PRIMARY KEY, agent_id VARCHAR(512) NOT NULL,
                        algorithm VARCHAR(32) NOT NULL, private_key_ciphertext BYTEA NOT NULL,
                        public_key_bytes BYTEA NOT NULL, revoked_at TIMESTAMPTZ,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            sql.execute("""
                    CREATE TABLE andalus_anp_agent_signing_keys (
                        agent_id VARCHAR(512) PRIMARY KEY,
                        key_id VARCHAR(512) NOT NULL UNIQUE REFERENCES andalus_anp_signing_keys(key_id),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            sql.execute("""
                    CREATE TABLE andalus_anp_revoked_key_ids (
                        key_id VARCHAR(512) PRIMARY KEY,
                        revoked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
        }
    }

    private static class DelegatingKeyStore implements AnpKeyStore {
        private final AnpKeyStore delegate;
        private DelegatingKeyStore(AnpKeyStore delegate) { this.delegate = delegate; }
        @Override public Optional<AnpIdentityKeyPair> getKey(String id) { return delegate.getKey(id); }
        @Override public Optional<AnpIdentityKeyPair> getActiveKey(String id) { return delegate.getActiveKey(id); }
        @Override public Optional<String> getAgentKeyId(String id) { return delegate.getAgentKeyId(id); }
        @Override public void storeKey(String id, AnpIdentityKeyPair pair) { delegate.storeKey(id, pair); }
        @Override public void revokeKey(String id) { delegate.revokeKey(id); }
    }

    private static final class TestProtector implements AnpKeyMaterialProtector {
        @Override public byte[] protect(String agent, String key, byte[] plain) {
            byte[] result = new byte[plain.length + 1];
            result[0] = 0x5a;
            System.arraycopy(plain, 0, result, 1, plain.length);
            return result;
        }
        @Override public byte[] unprotect(String agent, String key, byte[] cipher) {
            if (cipher == null || cipher.length < 2 || cipher[0] != 0x5a) {
                throw new SecurityException("Invalid test ciphertext");
            }
            return java.util.Arrays.copyOfRange(cipher, 1, cipher.length);
        }
    }
}
