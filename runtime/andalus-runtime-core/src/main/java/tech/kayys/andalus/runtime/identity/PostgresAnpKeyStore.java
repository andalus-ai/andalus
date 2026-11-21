package tech.kayys.andalus.runtime.identity;

import tech.kayys.andalus.anp.identity.AnpIdentityKeyPair;
import tech.kayys.andalus.anp.identity.AnpKeyStore;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.util.Arrays;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL-backed ANP key registry with terminal revocation.
 *
 * <p>Private key bytes are envelope-encrypted before persistence. The encryption key
 * remains in a KMS/HSM implementation of {@link AnpKeyMaterialProtector}; this class
 * never persists plaintext private material. Key IDs are immutable: reusing an ID for
 * different material or another agent is rejected, and a revoked ID can never be restored.
 */
public class PostgresAnpKeyStore implements AnpKeyStore {
    private static final String INSERT_KEY = """
            INSERT INTO andalus_anp_signing_keys
                (key_id, agent_id, algorithm, private_key_ciphertext, public_key_bytes)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (key_id) DO NOTHING
            """;
    private static final String SELECT_KEY_FOR_UPDATE = """
            SELECT agent_id, algorithm, private_key_ciphertext, public_key_bytes, revoked_at
            FROM andalus_anp_signing_keys WHERE key_id = ? FOR UPDATE
            """;
    private static final String UPSERT_DEFAULT = """
            INSERT INTO andalus_anp_agent_signing_keys (agent_id, key_id)
            VALUES (?, ?)
            ON CONFLICT (agent_id) DO UPDATE SET key_id = EXCLUDED.key_id,
                updated_at = CURRENT_TIMESTAMP
            """;

    private final DataSource dataSource;
    private final AnpKeyMaterialProtector protector;

    public PostgresAnpKeyStore(DataSource dataSource, AnpKeyMaterialProtector protector) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.protector = Objects.requireNonNull(protector, "protector");
    }

    @Override
    public Optional<AnpIdentityKeyPair> getKey(String keyId) {
        return findActive(keyId);
    }

    @Override
    public Optional<AnpIdentityKeyPair> getActiveKey(String keyId) {
        return findActive(keyId);
    }

    private Optional<AnpIdentityKeyPair> findActive(String keyId) {
        if (keyId == null || keyId.isBlank()) return Optional.empty();
        String sql = """
                SELECT agent_id, algorithm, private_key_ciphertext, public_key_bytes
                FROM andalus_anp_signing_keys WHERE key_id = ? AND revoked_at IS NULL
                FOR SHARE
                """;
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, keyId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        connection.commit();
                        return Optional.empty();
                    }
                    String agentId = result.getString("agent_id");
                    byte[] ciphertext = result.getBytes("private_key_ciphertext");
                    byte[] privateKey = null;
                    try {
                        privateKey = protector.unprotect(agentId, keyId, ciphertext);
                        if (privateKey == null || privateKey.length == 0) {
                            throw new IllegalStateException("KMS returned empty ANP private key material");
                        }
                        AnpIdentityKeyPair pair = new AnpIdentityKeyPair(keyId, result.getString("algorithm"),
                                privateKey, result.getBytes("public_key_bytes"));
                        connection.commit();
                        return Optional.of(pair);
                    } finally {
                        wipe(privateKey);
                        wipe(ciphertext);
                    }
                }
            } catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read active ANP signing key", exception);
        }
    }

    @Override
    public Optional<String> getAgentKeyId(String agentId) {
        if (agentId == null || agentId.isBlank()) return Optional.empty();
        String sql = """
                SELECT mapping.key_id
                FROM andalus_anp_agent_signing_keys mapping
                JOIN andalus_anp_signing_keys signing_key ON signing_key.key_id = mapping.key_id
                WHERE mapping.agent_id = ? AND signing_key.agent_id = mapping.agent_id
                  AND signing_key.revoked_at IS NULL
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, agentId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to resolve agent ANP signing key", exception);
        }
    }

    @Override
    public void storeKey(String agentId, AnpIdentityKeyPair keyPair) {
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId must not be blank");
        Objects.requireNonNull(keyPair, "keyPair");
        if (keyPair.keyId().isBlank()) throw new IllegalArgumentException("keyId must not be blank");
        if (!"Ed25519".equalsIgnoreCase(keyPair.algorithm())) {
            throw new IllegalArgumentException("Only Ed25519 ANP signing keys are supported");
        }
        if (!keyPair.canSign() || keyPair.publicKeyBytes() == null || keyPair.publicKeyBytes().length == 0) {
            throw new IllegalArgumentException("A complete Ed25519 key pair is required");
        }

        byte[] privateKey = keyPair.privateKeyBytes();
        byte[] ciphertext = null;
        byte[] existingPrivate = null;
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                // Check the permanent tombstone before invoking KMS. A revoked key ID
                // must never trigger encryption or any attempt to recreate its row.
                lockKeyId(connection, keyPair.keyId());
                if (isRevoked(connection, keyPair.keyId())) {
                    throw new IllegalStateException("A revoked ANP key identifier cannot be reactivated: "
                            + keyPair.keyId());
                }
                ciphertext = protector.protect(agentId, keyPair.keyId(), privateKey);
                if (ciphertext == null || ciphertext.length == 0) {
                    throw new IllegalStateException("KMS returned empty protected ANP key material");
                }
                try (PreparedStatement insert = connection.prepareStatement(INSERT_KEY)) {
                    insert.setString(1, keyPair.keyId());
                    insert.setString(2, agentId);
                    insert.setString(3, "Ed25519");
                    insert.setBytes(4, ciphertext);
                    insert.setBytes(5, keyPair.publicKeyBytes());
                    insert.executeUpdate();
                }

                try (PreparedStatement select = connection.prepareStatement(SELECT_KEY_FOR_UPDATE)) {
                    select.setString(1, keyPair.keyId());
                    try (ResultSet result = select.executeQuery()) {
                        if (!result.next()) throw new IllegalStateException("ANP key row missing after insert");
                        if (result.getTimestamp("revoked_at") != null) {
                            throw new IllegalStateException("A revoked ANP key identifier cannot be reactivated: "
                                    + keyPair.keyId());
                        }
                        if (!agentId.equals(result.getString("agent_id"))
                                || !"Ed25519".equalsIgnoreCase(result.getString("algorithm"))
                                || !MessageDigest.isEqual(keyPair.publicKeyBytes(), result.getBytes("public_key_bytes"))) {
                            throw new SecurityException("ANP key ID is already bound to different key material");
                        }
                        byte[] storedCiphertext = result.getBytes("private_key_ciphertext");
                        try {
                            existingPrivate = protector.unprotect(agentId, keyPair.keyId(), storedCiphertext);
                            if (!MessageDigest.isEqual(privateKey, existingPrivate)) {
                                throw new SecurityException("ANP key ID is already bound to different private material");
                            }
                        } finally {
                            wipe(storedCiphertext);
                        }
                    }
                }

                try (PreparedStatement mapping = connection.prepareStatement(UPSERT_DEFAULT)) {
                    mapping.setString(1, agentId);
                    mapping.setString(2, keyPair.keyId());
                    mapping.executeUpdate();
                }
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } finally {
                    wipe(privateKey);
                    wipe(ciphertext);
                    wipe(existingPrivate);
                }
            }
        } catch (SQLException exception) {
            wipe(privateKey);
            wipe(ciphertext);
            wipe(existingPrivate);
            throw new IllegalStateException("Unable to persist ANP signing key", exception);
        }
