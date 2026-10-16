package tech.kayys.andalus.api.rest.state;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactResourceTest {

    private ArtifactResource resource;
    private StateProducer producer;

    @BeforeEach
    void setUp() {
        producer = new StateProducer();
        resource = new ArtifactResource();
        resource.artifactStore = producer.produceArtifactStore();
        resource.metadataStore = producer.produceArtifactMetadataStore();
        var provStore = producer.produceProvenanceStore();
        resource.lineageResolver = producer.produceLineageResolver(provStore);
        resource.provenanceService = producer.produceProvenanceQueryService(provStore, resource.lineageResolver);
    }

    @Test
    void storesQueriesAndTracesArtifactLineage() {
        ArtifactResource.CreateArtifactRequest req = new ArtifactResource.CreateArtifactRequest(
                "model-weights.bin",
                "MODEL_OUTPUT",
                null,
                "SAMPLE_WEIGHTS_DATA",
                "application/octet-stream",
                Map.of("modelName", "aqli-v1")
        );

        Response createResp = resource.createArtifact(req);
        assertEquals(201, createResp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> createMap = (Map<String, Object>) createResp.getEntity();
        String artId = (String) createMap.get("artifactId");
        assertNotNull(artId);
        assertNotNull(createMap.get("digest"));

        // Get metadata
        Response metaResp = resource.getMetadata(artId);
        assertEquals(200, metaResp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> metaMap = (Map<String, Object>) metaResp.getEntity();
        assertEquals("model-weights.bin", metaMap.get("name"));
        assertEquals("MODEL_OUTPUT", metaMap.get("type"));

        // Download
        Response dlResp = resource.downloadContent(artId);
        assertEquals(200, dlResp.getStatus());
        assertNotNull(dlResp.getEntity());

        // Lineage trace
        Response lineageResp = resource.getArtifactLineage(artId);
        assertEquals(200, lineageResp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> lineageMap = (Map<String, Object>) lineageResp.getEntity();
        assertEquals(artId, lineageMap.get("artifactId"));
        assertNotNull(lineageMap.get("nodes"));
        assertNotNull(lineageMap.get("edges"));
    }
}
