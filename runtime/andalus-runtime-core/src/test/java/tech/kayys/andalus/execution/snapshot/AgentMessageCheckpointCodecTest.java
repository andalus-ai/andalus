package tech.kayys.andalus.execution.snapshot;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.agent.AgentRequest;
import tech.kayys.andalus.agent.AgentResponse;
import tech.kayys.andalus.artifact.ArtifactFormat;
import tech.kayys.andalus.extension.Id;
import tech.kayys.andalus.extension.Metadata;
import tech.kayys.andalus.identity.ResourceId.ArtifactId;
import tech.kayys.andalus.input.InputType;
import tech.kayys.andalus.resource.Artifact;
import tech.kayys.andalus.resource.ArtifactMetadata;
import tech.kayys.andalus.resource.TextArtifact;
import tech.kayys.andalus.resource.ResourceType;

class AgentMessageCheckpointCodecTest {
    private final CheckpointValueCodec values = new CheckpointValueCodec();
    private final ArtifactCheckpointCodec artifactCodec = new ArtifactCheckpointCodec(values);
    private final AgentMessageCheckpointCodec codec = new AgentMessageCheckpointCodec(values, artifactCodec);
    private final ArtifactId artifactId =
            new ArtifactId(Id.fromString("2d13a85d-7e7c-4ad5-a48e-2f3d9a7d1c21"));

    @Test
    void requestRoundTripsAllFieldsAndNestedValues() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("trace", "trace-17");
        metadata.put("nullable", null);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("attempt", 3);
        parameters.put("flags", List.of(true, false));
        Artifact attachment = artifact("request.txt", "request body");
        Instant timestamp = Instant.parse("2026-11-21T12:30:00Z");
        AgentRequest original = new AgentRequest("request-1", "session-2", "tenant-3", "user-4",
                InputType.WEBHOOK, "run task", metadata, List.of(attachment), parameters, timestamp);

        AgentRequest restored = codec.decode(codec.encode(original));

        assertEquals(original.id(), restored.id());
        assertEquals(original.sessionId(), restored.sessionId());
        assertEquals(original.tenantId(), restored.tenantId());
        assertEquals(original.userId(), restored.userId());
        assertEquals(original.type(), restored.type());
        assertEquals(original.content(), restored.content());
        assertEquals(original.timestamp(), restored.timestamp());
        assertEquals("trace-17", restored.metadata().get("trace"));
        assertTrue(restored.metadata().containsKey("nullable"));
        assertNull(restored.metadata().get("nullable"));
        assertEquals(java.math.BigInteger.valueOf(3), restored.parameters().get("attempt"));
        assertEquals(parameters.get("flags"), restored.parameters().get("flags"));
        assertEquals(1, restored.artifacts().size());
        assertEquals("request body", restored.artifacts().get(0).asString());
    }

    @Test
    void responseRoundTripsFailureAndResponseMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("model", "test-model");
        metadata.put("optional", null);
        Map<String, Object> data = Map.of("tokens", 42, "cached", true);
        Instant timestamp = Instant.parse("2026-11-21T12:31:00Z");
        AgentResponse original = new AgentResponse("response-1", "session-2", null, "error",
                List.of(artifact("error.txt", "failure details")), metadata, data,
                false, "tool unavailable", 125, timestamp);

        AgentResponse restored = codec.decode(codec.encode(original));

        assertEquals(original.id(), restored.id());
        assertEquals(original.sessionId(), restored.sessionId());
        assertEquals(original.content(), restored.content());
        assertEquals(original.type(), restored.type());
        assertFalse(restored.success());
        assertEquals(original.error(), restored.error());
        assertEquals(original.responseTimeMs(), restored.responseTimeMs());
        assertEquals(original.timestamp(), restored.timestamp());
        assertTrue(restored.metadata().containsKey("optional"));
        assertNull(restored.metadata().get("optional"));
        assertEquals(java.math.BigInteger.valueOf(42), restored.data().get("tokens"));
        assertEquals(true, restored.data().get("cached"));
        assertEquals("failure details", restored.artifacts().get(0).asString());
    }

    @Test
    void rejectsUnknownNestedValueTypesInsteadOfSerializingThem() {
        AgentRequest request = new AgentRequest("r", null, null, null, InputType.TEXT,
                "hello", Map.of(), List.of(), Map.of("unsafe", new Object()), Instant.EPOCH);
        assertThrows(IllegalArgumentException.class, () -> codec.encode(request));
    }

    private Artifact artifact(String name, String body) {
        return new TextArtifact(artifactId, new ResourceType.Custom("document"), ArtifactFormat.TEXT,
                body, Metadata.builder().name(name).build(),
                new ArtifactMetadata("text/plain", body.length(), null, null,
                        null, null, null, "en", Map.of()));
    }
}
