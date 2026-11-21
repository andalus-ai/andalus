package tech.kayys.andalus.runtime.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.postgresql.ds.PGSimpleDataSource;
import tech.kayys.andalus.anp.identity.DidWbaDocument;
import tech.kayys.andalus.anp.identity.DidWbaVerificationMethod;
import tech.kayys.andalus.anp.identity.TrustedDidDocument;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class PostgresTrustedDidDocumentStoreTest {
    private static final String DID = "did:wba:agents.example.com:finance";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static PostgresTrustedDidDocumentStore firstNode;
    private static PostgresTrustedDidDocumentStore secondNode;

    @BeforeAll
    static void initializeDatabase() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE andalus_trusted_did_documents (
                        did VARCHAR(512) PRIMARY KEY,
                        revision BIGINT NOT NULL CHECK (revision > 0),
                        document_json JSONB NOT NULL,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }

        ObjectMapper mapper = new ObjectMapper();
        firstNode = new PostgresTrustedDidDocumentStore(dataSource, mapper);
        secondNode = new PostgresTrustedDidDocumentStore(dataSource, mapper);
    }

    @Test
    void persistsRevisionAndDocumentAcrossIndependentStoreInstances() {
        DidWbaDocument initial = document("key-1");
        DidWbaDocument rotated = document("key-2");

        firstNode.register(new TrustedDidDocument(initial, 1));
        secondNode.register(new TrustedDidDocument(rotated, 2));

        TrustedDidDocument loaded = firstNode.find(DID).orElseThrow();
        assertEquals(2, loaded.revision());
        assertEquals(rotated, loaded.document());
    }

    @Test
    void rejectsStaleRevisionAcrossIndependentStoreInstances() {
        DidWbaDocument current = document("key-3");
        firstNode.register(new TrustedDidDocument(current, 7));

        assertThrows(SecurityException.class,
                () -> secondNode.register(new TrustedDidDocument(document("key-2"), 6)));

        assertEquals(new TrustedDidDocument(current, 7), secondNode.find(DID).orElseThrow());
    }

    @Test
    void rejectsDifferentDocumentAtSameRevision() {
        DidWbaDocument current = document("key-4");
        firstNode.register(new TrustedDidDocument(current, 9));

        assertThrows(SecurityException.class,
                () -> secondNode.register(new TrustedDidDocument(document("key-5"), 9)));
        assertEquals(current, firstNode.find(DID).orElseThrow().document());
    }

    @Test
    void acceptsIdenticalReplayAtSameRevision() {
        DidWbaDocument current = document("key-6");
        firstNode.register(new TrustedDidDocument(current, 11));

        TrustedDidDocument replay = secondNode.register(new TrustedDidDocument(current, 11));

        assertEquals(new TrustedDidDocument(current, 11), replay);
    }

    private static DidWbaDocument document(String keyId) {
        return new DidWbaDocument(DID,
                List.of(DidWbaVerificationMethod.ed25519(DID + "#" + keyId, DID, "z" + keyId)),
                List.of("#" + keyId), Map.of());
    }
}
