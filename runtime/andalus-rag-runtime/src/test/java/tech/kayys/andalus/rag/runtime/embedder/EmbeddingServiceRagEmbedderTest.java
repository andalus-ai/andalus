package tech.kayys.andalus.rag.runtime.embedder;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.embedding.EmbeddingModuleConfig;
import tech.kayys.andalus.embedding.EmbeddingProviderRegistry;
import tech.kayys.andalus.embedding.EmbeddingService;
import tech.kayys.andalus.embedding.provider.DeterministicHashEmbeddingProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingServiceRagEmbedderTest {

    private static EmbeddingService service() {
        EmbeddingModuleConfig config = new EmbeddingModuleConfig();
        config.setDefaultProvider("hash");
        config.setDefaultModel("hash-384");
        return new EmbeddingService(
                new EmbeddingProviderRegistry(List.of(new DeterministicHashEmbeddingProvider())), config);
    }

    @Test
    void embedsThroughTheConfiguredService() {
        EmbeddingServiceRagEmbedder e = new EmbeddingServiceRagEmbedder(service());
        float[] v = e.embed("liability clause");
        assertEquals(384, v.length);
        assertEquals(384, e.dimensions());
        assertFalse(Arrays.equals(new float[384], v), "must not be zero vectors");
        assertArrayEquals(v, e.embed("liability clause"));
    }

    @Test
    void batchesAndKeepsOrder() {
        EmbeddingServiceRagEmbedder e = new EmbeddingServiceRagEmbedder(
                service(), null, null, null, 2, Duration.ofSeconds(10));
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 5; i++) texts.add("text number " + i);
        List<float[]> all = e.embedAll(texts);
        assertEquals(5, all.size());
        for (int i = 0; i < 5; i++) {
            assertArrayEquals(e.embed(texts.get(i)), all.get(i));
        }
        assertTrue(e.embedAll(List.of()).isEmpty());
    }

    @Test
    void modelOverrideChangesDimensions() {
        EmbeddingServiceRagEmbedder e = new EmbeddingServiceRagEmbedder(
                service(), null, "hash", "hash-1536", 8, Duration.ofSeconds(10));
        assertEquals(1536, e.dimensions());
    }

    @Test
    void failureDoesNotEchoInput() {
        EmbeddingServiceRagEmbedder e = new EmbeddingServiceRagEmbedder(
                service(), null, "no-such-provider", null, 8, Duration.ofSeconds(10));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> e.embed("SECRET-TEXT"));
        assertFalse(String.valueOf(ex.getMessage()).contains("SECRET-TEXT"));
    }

    @Test
    void rejectsBadBatchSize() {
        assertThrows(IllegalArgumentException.class, () ->
                new EmbeddingServiceRagEmbedder(service(), null, null, null, 0, Duration.ofSeconds(1)));
    }
}
