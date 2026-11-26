package tech.kayys.andalus.runtime.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.andalus.anp.identity.DidWbaDocument;
import tech.kayys.andalus.anp.identity.TrustedDidDocument;
import tech.kayys.andalus.anp.identity.TrustedDidDocumentStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL-backed trusted DID document store.
 *
 * <p>The row lock and unique-key insert serialize registrations for the same DID
 * across runtime instances. Document JSON and its trusted revision are committed
 * in one database transaction.
 */
@ApplicationScoped
public class PostgresTrustedDidDocumentStore implements TrustedDidDocumentStore {
    private static final String INSERT_IF_ABSENT = """
            INSERT INTO andalus_trusted_did_documents (did, revision, document_json)
            VALUES (?, ?, ?::jsonb)
            ON CONFLICT (did) DO NOTHING
            """;

    private static final String SELECT_FOR_UPDATE = """
            SELECT revision, document_json::text
            FROM andalus_trusted_did_documents
            WHERE did = ?
            FOR UPDATE
            """;

    private static final String UPDATE_IF_REVISION_MATCHES = """
            UPDATE andalus_trusted_did_documents
            SET revision = ?, document_json = ?::jsonb, updated_at = CURRENT_TIMESTAMP
            WHERE did = ? AND revision = ?
            """;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    @Inject
    public PostgresTrustedDidDocumentStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public Optional<TrustedDidDocument> find(String did) {
        Objects.requireNonNull(did, "did");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT revision, document_json::text FROM andalus_trusted_did_documents WHERE did = ?")) {
            statement.setString(1, did);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(readSnapshot(result));
            }
        } catch (SQLException | JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read trusted DID document", exception);
        }
    }

    @Override
    public TrustedDidDocument register(TrustedDidDocument candidate) {
        return register(candidate, null);
    }

    /** Registers a rotation snapshot only while its durable lease fence is current. */
    public TrustedDidDocument register(TrustedDidDocument candidate, AnpKeyRotationFence fence) {
        Objects.requireNonNull(candidate, "candidate");
        String did = candidate.document().id();
        String documentJson = writeDocument(candidate.document());

        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (fence != null) {
                    PostgresAnpKeyRotationFenceGuard.requireCurrent(connection, fence);
                }
                if (insertIfAbsent(connection, candidate, documentJson)) {
                    connection.commit();
                    return candidate;
                }

                TrustedDidDocument current = selectForUpdate(connection, did)
                        .orElseThrow(() -> new IllegalStateException(
                                "Trusted DID row disappeared during registration: " + did));

                if (candidate.revision() < current.revision()) {
                    throw new SecurityException("Stale DID document revision rejected for " + did);
                }
                if (candidate.revision() == current.revision()) {
                    if (!candidate.document().equals(current.document())) {
                        throw new SecurityException(
                                "DID document content changed without a revision increase for " + did);
                    }
                    connection.commit();
                    return current;
                }

                try (PreparedStatement update = connection.prepareStatement(UPDATE_IF_REVISION_MATCHES)) {
                    update.setLong(1, candidate.revision());
                    update.setString(2, documentJson);
                    update.setString(3, did);
                    update.setLong(4, current.revision());
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException("Trusted DID revision changed unexpectedly for " + did);
                    }
                }
                connection.commit();
                return candidate;
            } catch (JsonProcessingException exception) {
                rollbackQuietly(connection, exception);
                throw new IllegalStateException("Unable to decode trusted DID document", exception);
            } catch (RuntimeException | SQLException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to persist trusted DID document", exception);
        }
    }

    private boolean insertIfAbsent(Connection connection, TrustedDidDocument candidate, String json)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(INSERT_IF_ABSENT)) {
            insert.setString(1, candidate.document().id());
            insert.setLong(2, candidate.revision());
            insert.setString(3, json);
            return insert.executeUpdate() == 1;
        }
    }

    private Optional<TrustedDidDocument> selectForUpdate(Connection connection, String did)
            throws SQLException, JsonProcessingException {
        try (PreparedStatement select = connection.prepareStatement(SELECT_FOR_UPDATE)) {
            select.setString(1, did);
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? Optional.of(readSnapshot(result)) : Optional.empty();
            }
        }
    }

    private TrustedDidDocument readSnapshot(ResultSet result)
            throws SQLException, JsonProcessingException {
        long revision = result.getLong(1);
        DidWbaDocument document = objectMapper.readValue(result.getString(2), DidWbaDocument.class);
        return new TrustedDidDocument(document, revision);
    }

    private String writeDocument(DidWbaDocument document) {
        try {
            return objectMapper.writeValueAsString(document);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize trusted DID document", exception);
        }
    }

    private static void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
