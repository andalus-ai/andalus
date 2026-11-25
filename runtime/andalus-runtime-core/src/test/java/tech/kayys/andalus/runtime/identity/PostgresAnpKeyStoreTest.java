package tech.kayys.andalus.runtime.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.postgresql.ds.PGSimpleDataSource;
import tech.kayys.andalus.anp.identity.AnpIdentityKeyPair;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PostgresAnpKeyStoreTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private PostgresAnpKeyStore store;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setURL(POSTGRES.getJdbcUrl());
        postgres.setUser(POSTGRES.getUsername());
        postgres.setPassword(POSTGRES.getPassword());
        dataSource = postgres;
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("DROP TABLE IF EXISTS andalus_anp_agent_signing_keys");
            sql.execute("DROP TABLE IF EXISTS andalus_anp_signing_keys");
            sql.execute("DROP TABLE IF EXISTS andalus_anp_revoked_key_ids");
            sql.execute("""
                    CREATE TABLE andalus_anp_signing_keys (
                        key_id VARCHAR(512) PRIMARY KEY, agent_id VARCHAR(512) NOT NULL,
                        algorithm VARCHAR(32) NOT NULL CHECK (algorithm = 'Ed25519'),
                        private_key_ciphertext BYTEA NOT NULL, public_key_bytes BYTEA NOT NULL,
                        revoked_at TIMESTAMPTZ NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CHECK (octet_length(private_key_ciphertext) > 0 AND octet_length(public_key_bytes) > 0)
                    )
                    """);
            sql.execute("""
                    CREATE TABLE andalus_anp_agent_signing_keys (
                        agent_id VARCHAR(512) PRIMARY KEY,
                        key_id VARCHAR(512) NOT NULL UNIQUE REFERENCES andalus_anp_signing_keys(key_id),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            sql.execute("""
                    CREATE TABLE andalus_anp_revoked_key_ids (
                        key_id VARCHAR(512) PRIMARY KEY,
                        revoked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
        store = new PostgresAnpKeyStore(dataSource, new PrefixProtector());
    }

    @Test
    void persistsKeyAndResolvesDefaultAcrossStoreInstances() {
        AnpIdentityKeyPair pair = pair("did:wba:example#key-1", "private-one");
        store.storeKey("did:wba:example", pair);

        PostgresAnpKeyStore restarted = new PostgresAnpKeyStore(dataSource, new PrefixProtector());
        assertEquals(pair.keyId(), restarted.getAgentKeyId("did:wba:example").orElseThrow());
        assertArrayEquals(pair.privateKeyBytes(), restarted.getActiveKey(pair.keyId()).orElseThrow().privateKeyBytes());
        assertArrayEquals(pair.publicKeyBytes(), restarted.getKey(pair.keyId()).orElseThrow().publicKeyBytes());
    }

    @Test
    void rejectsKeyIdReuseWithDifferentMaterial() {
        store.storeKey("did:wba:example", pair("did:wba:example#key-1", "private-one"));
        assertThrows(SecurityException.class, () ->
                store.storeKey("did:wba:example", pair("did:wba:example#key-1", "private-two")));
    }

    @Test
    void revocationIsDurableAndCannotBeReversed() {
        AnpIdentityKeyPair pair = pair("did:wba:example#key-1", "private-one");
        store.storeKey("did:wba:example", pair);
        store.revokeKey(pair.keyId());

        assertTrue(new PostgresAnpKeyStore(dataSource, new PrefixProtector())
                .getActiveKey(pair.keyId()).isEmpty());
        assertTrue(store.getAgentKeyId("did:wba:example").isEmpty());
        assertThrows(IllegalStateException.class, () -> store.storeKey("did:wba:example", pair));
    }

    @Test
    void revokingUnknownIdentifierCreatesPermanentTombstone() {
        store.revokeKey("did:wba:example#never-registered");
        assertThrows(IllegalStateException.class, () ->
                store.storeKey("did:wba:example", pair("did:wba:example#never-registered", "private-one")));
    }

    @Test
    void rejectsRevokedKeyBeforeCallingProtector() {
        String keyId = "did:wba:example#revoked-before-kms";
        store.revokeKey(keyId);
        CountingProtector counting = new CountingProtector();
        PostgresAnpKeyStore guarded = new PostgresAnpKeyStore(dataSource, counting);

        assertThrows(IllegalStateException.class, () ->
                guarded.storeKey("did:wba:example", pair(keyId, "private-one")));
        assertEquals(0, counting.protectCalls);
    }

    @Test
    void wipesTransientPlaintextAndCiphertextBuffersAfterStore() {
        PrefixProtector tracking = new PrefixProtector();
        PostgresAnpKeyStore guarded = new PostgresAnpKeyStore(dataSource, tracking);
        guarded.storeKey("did:wba:example", pair("did:wba:example#key-wipe", "private-one"));

        assertNotNull(tracking.lastPlaintext);
        assertNotNull(tracking.lastCiphertext);
        assertArrayEquals(new byte[tracking.lastPlaintext.length], tracking.lastPlaintext);
        assertArrayEquals(new byte[tracking.lastCiphertext.length], tracking.lastCiphertext);
    }

    @Test
    void databaseNeverReceivesPlaintextPrivateKeyBytes() throws Exception {
        byte[] privateBytes = "private-one".getBytes(StandardCharsets.UTF_8);
        store.storeKey("did:wba:example", new AnpIdentityKeyPair(
                "did:wba:example#key-1", "Ed25519", privateBytes, new byte[]{4, 5, 6}));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT private_key_ciphertext FROM andalus_anp_signing_keys WHERE key_id = 'did:wba:example#key-1'")) {
            assertTrue(rows.next());
            assertFalse(Arrays.equals(privateBytes, rows.getBytes(1)));
        }
    }

    private static AnpIdentityKeyPair pair(String keyId, String privateText) {
        return new AnpIdentityKeyPair(keyId, "Ed25519",
                privateText.getBytes(StandardCharsets.UTF_8), new byte[]{1, 2, 3, 4});
    }

    private static final class CountingProtector implements AnpKeyMaterialProtector {
        private int protectCalls;

        @Override
        public byte[] protect(String agentId, String keyId, byte[] plaintext) {
            protectCalls++;
            return Arrays.copyOf(plaintext, plaintext.length);
        }

        @Override
        public byte[] unprotect(String agentId, String keyId, byte[] ciphertext) {
            return Arrays.copyOf(ciphertext, ciphertext.length);
        }
    }

    /** Test double only; production must inject a KMS/HSM-backed authenticated protector. */
    private static final class PrefixProtector implements AnpKeyMaterialProtector {
        private byte[] lastPlaintext;
        private byte[] lastCiphertext;

        @Override
        public byte[] protect(String agentId, String keyId, byte[] plaintext) {
            lastPlaintext = plaintext;
            byte[] result = new byte[plaintext.length + 1];
            result[0] = 42;
            System.arraycopy(plaintext, 0, result, 1, plaintext.length);
            lastCiphertext = result;
            return result;
        }

        @Override
        public byte[] unprotect(String agentId, String keyId, byte[] ciphertext) {
            if (ciphertext == null || ciphertext.length < 2 || ciphertext[0] != 42) {
                throw new SecurityException("Invalid test ciphertext");
            }
            return Arrays.copyOfRange(ciphertext, 1, ciphertext.length);
        }
    }
}
