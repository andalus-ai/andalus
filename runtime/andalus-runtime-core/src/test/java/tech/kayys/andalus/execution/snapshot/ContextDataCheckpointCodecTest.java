package tech.kayys.andalus.execution.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.context.ContextData;
import tech.kayys.andalus.context.Document;

class ContextDataCheckpointCodecTest {
    private final ContextDataCheckpointCodec codec =
            new ContextDataCheckpointCodec(new CheckpointValueCodec());

    @Test
    void roundTripsDocumentsMemoriesKnowledgeAndStructuredData() {
        ContextData original = new ContextData(
                "ctx-1", "query", "original", List.of(new Document(
                        "doc-1", "body", "title", "source",
                        Map.of("rank", 2), List.of(0.25d, 0.75d))),
                Arrays.asList(Map.of("memory", "value"), null),
                List.of("fact", 7), Map.of("nested", Map.of("enabled", true)),
                Map.of("origin", "test"), 0.875d, 42L, "vector_rag");

        ContextData restored = codec.decode(codec.encode(original));

        assertEquals(original.id(), restored.id());
        assertEquals(original.query(), restored.query());
        assertEquals(original.originalQuery(), restored.originalQuery());
        assertEquals(original.documents(), restored.documents());
        assertEquals(List.of(Map.of("memory", "value"), null), restored.memories());
        assertEquals(List.of("fact", new java.math.BigInteger("7")), restored.knowledge());
        assertEquals(Map.of("nested", Map.of("enabled", true)), restored.structuredData());
        assertEquals(original.metadata(), restored.metadata());
        assertEquals(original.relevanceScore(), restored.relevanceScore());
        assertEquals(original.retrievalTimeMs(), restored.retrievalTimeMs());
        assertEquals(original.contextType(), restored.contextType());
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        var encoded = codec.encode(ContextData.empty());
        var unsupported = new ContextDataCheckpointCodec.ContextDataValue(
                99, encoded.id(), encoded.query(), encoded.originalQuery(),
                encoded.documents(), encoded.memories(), encoded.knowledge(),
                encoded.structuredData(), encoded.metadata(), encoded.relevanceScore(),
                encoded.retrievalTimeMs(), encoded.contextType());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(unsupported));
    }

    @Test
    void rejectsUnknownMemoryAndKnowledgeObjectTypes() {
        ContextData context = new ContextData("ctx", null, null, List.of(),
                List.of(new Object()), List.of(), Map.of(), Map.of(), 0, 0, "test");
        assertThrows(IllegalArgumentException.class, () -> codec.encode(context));
    }
}
