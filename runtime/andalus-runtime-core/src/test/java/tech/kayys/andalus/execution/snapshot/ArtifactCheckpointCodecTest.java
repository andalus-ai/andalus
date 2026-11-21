package tech.kayys.andalus.execution.snapshot;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.artifact.ArtifactFormat;
import tech.kayys.andalus.extension.Id;
import tech.kayys.andalus.extension.Metadata;
import tech.kayys.andalus.identity.ResourceId.ArtifactId;
import tech.kayys.andalus.resource.Artifact;
import tech.kayys.andalus.resource.ArtifactMetadata;
import tech.kayys.andalus.resource.BinaryArtifact;
import tech.kayys.andalus.resource.JsonArtifact;
import tech.kayys.andalus.resource.ResourceType;
import tech.kayys.andalus.resource.TextArtifact;

class ArtifactCheckpointCodecTest {
    private final CheckpointValueCodec values = new CheckpointValueCodec();
    private final ArtifactCheckpointCodec codec = new ArtifactCheckpointCodec(values);
    private final ArtifactId id = new ArtifactId(Id.fromString("2d13a85d-7e7c-4ad5-a48e-2f3d9a7d1c21"));
    private final ResourceType type = new ResourceType.Custom("document");

    @Test
    void textArtifactRoundTripsIdentityAndAllMetadata() {
        Metadata metadata = Metadata.builder().name("contract").description("signed")
                .label("classification", "confidential").attribute("nullable", null)
                .createdAt(Instant.parse("2026-11-20T10:00:00Z")).build();
        ArtifactMetadata details = new ArtifactMetadata("text/plain", 12, "file://contract",
                Duration.ofSeconds(3), 2, 3, 1, "en", Map.of("source", "user"));
        TextArtifact original = new TextArtifact(id, type, ArtifactFormat.MARKDOWN,
                "# Contract", metadata, details);

        TextArtifact restored = assertInstanceOf(TextArtifact.class, codec.decode(codec.encode(original)));
        assertEquals(original.id(), restored.id());
        assertEquals(original.type(), restored.type());
        assertEquals(original.format(), restored.format());
        assertEquals(original.data(), restored.data());
        assertEquals("confidential", restored.metadata().labels().get("classification"));
        assertTrue(restored.metadata().attributes().containsKey("nullable"));
        assertNull(restored.metadata().attributes().get("nullable"));
        assertEquals(details, restored.artifactMetadata());
    }

    @Test
    void jsonArtifactRoundTripsRawAndParsedContent() {
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("ok", true);
        parsed.put("count", 7);
        JsonArtifact original = new JsonArtifact(id, type, ArtifactFormat.JSON,
                "{\"ok\":true,\"count\":7}", parsed, Metadata.empty(),
                new ArtifactMetadata("application/json", 22, null, null, null, null, null, "en", Map.of()));

        JsonArtifact restored = assertInstanceOf(JsonArtifact.class, codec.decode(codec.encode(original)));
        assertEquals(original.id(), restored.id());
        assertEquals(original.json(), restored.json());
        assertEquals(parsed, restored.parsed());
        assertEquals(original.artifactMetadata(), restored.artifactMetadata());
    }

    @Test
    void binaryArtifactRoundTripsExactBytesAndRejectsUnknownImplementations() {
        byte[] payload = new byte[] { 0, 1, -1, 42 };
        BinaryArtifact original = new BinaryArtifact(id, type, ArtifactFormat.BINARY, payload,
                Metadata.empty(), new ArtifactMetadata("application/octet-stream", payload.length,
                "blob://1", null, null, null, null, null, Map.of()));
        BinaryArtifact restored = assertInstanceOf(BinaryArtifact.class, codec.decode(codec.encode(original)));
        assertArrayEquals(payload, restored.asBytes());
        assertEquals(original.id(), restored.id());
        assertEquals(original.artifactMetadata(), restored.artifactMetadata());

        Artifact unsupported = new Artifact() {
            public ArtifactId id() { return id; }
            public ResourceType type() { return type; }
            public ArtifactFormat format() { return ArtifactFormat.TEXT; }
            public Object content() { return "custom"; }
            public String asString() { return "custom"; }
            public byte[] asBytes() { return new byte[0]; }
            public Metadata metadata() { return Metadata.empty(); }
        };
        assertThrows(IllegalArgumentException.class, () -> codec.encode(unsupported));
    }
}
