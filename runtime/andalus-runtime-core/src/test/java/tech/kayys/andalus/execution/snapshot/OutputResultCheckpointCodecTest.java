package tech.kayys.andalus.execution.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.spi.output.OutputResult;
import tech.kayys.andalus.spi.output.OutputType;

class OutputResultCheckpointCodecTest {
    private final OutputResultCheckpointCodec codec = new OutputResultCheckpointCodec(
            new CheckpointValueCodec(), new ArtifactCheckpointCodec(new CheckpointValueCodec()));

    @Test
    void roundTripsOutputFieldsAndMetadata() {
        Instant timestamp = Instant.parse("2026-11-25T06:00:00Z");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attempt", 3);
        metadata.put("nullable", null);
        metadata.put("nested", Map.of("accepted", true));
        OutputResult original = new OutputResult("output-1", OutputType.TEXT, "hello",
                java.util.List.of(), metadata, false, timestamp, "session-1", "user-1");

        OutputResult restored = codec.decode(codec.encode(original));

        assertEquals(original.id(), restored.id());
        assertEquals(original.type(), restored.type());
        assertEquals(original.content(), restored.content());
        assertEquals(original.artifacts(), restored.artifacts());
        assertEquals(java.math.BigInteger.valueOf(3), restored.metadata().get("attempt"));
        assertNull(restored.metadata().get("nullable"));
        assertEquals(Map.of("accepted", true), restored.metadata().get("nested"));
        assertEquals(original.isComplete(), restored.isComplete());
        assertEquals(timestamp, restored.timestamp());
        assertEquals(original.sessionId(), restored.sessionId());
        assertEquals(original.recipient(), restored.recipient());
    }

    @Test
    void preservesNullOutputResult() {
        assertNull(codec.encode(null));
        assertNull(codec.decode(null));
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        var encoded = codec.encode(OutputResult.of("hello"));
        var unsupported = new OutputResultCheckpointCodec.OutputResultValue(
                99, encoded.id(), encoded.type(), encoded.content(), encoded.artifacts(),
                encoded.metadata(), encoded.complete(), encoded.timestamp(),
                encoded.sessionId(), encoded.recipient());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(unsupported));
    }

    @Test
    void rejectsUnknownOutputType() {
        var encoded = codec.encode(OutputResult.of("hello"));
        var unsupported = new OutputResultCheckpointCodec.OutputResultValue(
                encoded.schemaVersion(), encoded.id(), "NOT_A_REAL_OUTPUT_TYPE",
                encoded.content(), encoded.artifacts(), encoded.metadata(), encoded.complete(),
                encoded.timestamp(), encoded.sessionId(), encoded.recipient());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(unsupported));
    }

    @Test
    void rejectsUnsupportedMetadataValues() {
        OutputResult original = new OutputResult("output-2", OutputType.TEXT, "hello",
                java.util.List.of(), Map.of("unsupported", new Object()), true,
                Instant.parse("2026-11-25T06:00:00Z"), null, null);
        assertThrows(IllegalArgumentException.class, () -> codec.encode(original));
    }
}
