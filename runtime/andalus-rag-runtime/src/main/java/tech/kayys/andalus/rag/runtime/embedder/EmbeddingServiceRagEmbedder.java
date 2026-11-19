package tech.kayys.andalus.rag.runtime.embedder;

import tech.kayys.andalus.embedding.EmbeddingRequest;
import tech.kayys.andalus.embedding.EmbeddingResponse;
import tech.kayys.andalus.embedding.EmbeddingService;
import tech.kayys.andalus.rag.spi.RagEmbedder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link RagEmbedder} backed by the configured {@link EmbeddingService}, so RAG uses the same
 * provider, model, cache and tenant strategy as the rest of Andalus instead of zero vectors.
 *
 * <p>The SPI is synchronous, so each call blocks up to {@code timeout}. Inputs are sent in
 * batches of {@code batchSize}. The returned vector count and sizes are validated, and a
 * failure never echoes the input text.
 */
public class EmbeddingServiceRagEmbedder implements RagEmbedder {

    public static final int DEFAULT_BATCH_SIZE = 64;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final EmbeddingService service;
    private final String tenantId;
    private final String provider;
    private final String model;
    private final int batchSize;
    private final Duration timeout;
    private volatile int dims;

    public EmbeddingServiceRagEmbedder(EmbeddingService service) {
        this(service, null, null, null, DEFAULT_BATCH_SIZE, DEFAULT_TIMEOUT);
    }

    /**
     * @param tenantId tenant whose embedding strategy applies, or null for the default
     * @param provider provider override, or null/blank for the configured one
     * @param model    model override, or null/blank for the configured one
     */
    public EmbeddingServiceRagEmbedder(EmbeddingService service, String tenantId, String provider,
                                       String model, int batchSize, Duration timeout) {
        this.service = Objects.requireNonNull(service, "service");
        if (batchSize < 1 || batchSize > 2048) {
            throw new IllegalArgumentException("batchSize must be between 1 and 2048");
        }
        this.tenantId = tenantId;
        this.provider = provider;
        this.model = model;
        this.batchSize = batchSize;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public float[] embed(String text) {
        List<float[]> out = embedAll(List.of(text == null ? "" : text));
        return out.get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        Objects.requireNonNull(texts, "texts");
        List<float[]> result = new ArrayList<>(texts.size());
        if (texts.isEmpty()) {
            return result;
        }
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = new ArrayList<>(texts.subList(from, Math.min(texts.size(), from + batchSize)));
            batch.replaceAll(t -> t == null ? "" : t);
            EmbeddingResponse response;
            try {
                response = service.embedForTenant(tenantId, new EmbeddingRequest(batch, model, provider, null))
                        .await().atMost(timeout);
            } catch (RuntimeException e) {
                throw new IllegalStateException("embedding failed: " + e.getClass().getSimpleName(), e);
            }
            List<float[]> vectors = response.embeddings();
            if (vectors == null || vectors.size() != batch.size()) {
                throw new IllegalStateException("embedding provider returned "
                        + (vectors == null ? 0 : vectors.size()) + " vectors for " + batch.size() + " inputs");
            }
            for (float[] v : vectors) {
                if (v == null || v.length == 0) {
                    throw new IllegalStateException("embedding provider returned an empty vector");
                }
                if (dims == 0) {
                    dims = v.length;
                } else if (v.length != dims) {
                    throw new IllegalStateException("embedding dimension changed from " + dims + " to " + v.length);
                }
            }
            result.addAll(vectors);
        }
        return result;
    }

    /** Vector size; probes the provider once if nothing has been embedded yet. */
    @Override
    public int dimensions() {
        int d = dims;
        if (d == 0) {
            embed("dimension probe");
            d = dims;
        }
        return d;
    }
}
