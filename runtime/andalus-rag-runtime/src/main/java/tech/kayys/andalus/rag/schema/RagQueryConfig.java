package tech.kayys.andalus.rag.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Top-level configuration DTO for the RAG executor.
 *
 * Fields mirror the context keys consumed by {@code RagExecutor.execute()}.
 */
public class RagQueryConfig {

    @JsonProperty("query")
    @JsonPropertyDescription("The user question or search query. Also accepted as 'question' or 'prompt'. Required.")
    private String query;

    @JsonProperty("collection")
    @JsonPropertyDescription("Name of the vector store collection to retrieve from. Defaults to 'default'.")
    private String collection = "default";

    @JsonProperty("tenantId")
    @JsonPropertyDescription("Tenant identifier for multi-tenant deployments. Also accepted as 'tenant'. Defaults to 'default-tenant'.")
    private String tenantId = "default-tenant";

    @JsonProperty("topK")
    @JsonPropertyDescription("Shorthand for retrieval.topK — number of documents to retrieve. Defaults to 5.")
    private Integer topK = 5;

    @JsonProperty("minSimilarity")
    @JsonPropertyDescription("Shorthand for retrieval.minSimilarity — minimum cosine similarity (0.0–1.0). Defaults to 0.5.")
    private Float minSimilarity = 0.5f;

    @JsonProperty("provider")
    @JsonPropertyDescription("Shorthand for generation.provider — LLM provider. E.g. aqli, openai, anthropic. Defaults to 'aqli'.")
    private String provider = "aqli";

    @JsonProperty("model")
    @JsonPropertyDescription("Shorthand for generation.model — LLM model id. E.g. Qwen/Qwen2.5-0.5B-Instruct.")
    private String model = "Qwen/Qwen2.5-0.5B-Instruct";

    @JsonProperty("temperature")
    @JsonPropertyDescription("Shorthand for generation.temperature (0.0–2.0). Defaults to 0.7.")
    private Float temperature;

    @JsonProperty("maxTokens")
    @JsonPropertyDescription("Shorthand for generation.maxTokens. Defaults to 1024.")
    private Integer maxTokens;

    @JsonProperty("systemPrompt")
    @JsonPropertyDescription("Shorthand for generation.systemPrompt prepended to every request.")
    private String systemPrompt;

    @JsonProperty("retrievalConfig")
    @JsonPropertyDescription("Full retrieval configuration. Values here override the shorthand fields above.")
    private RagRetrievalConfig retrievalConfig;

    @JsonProperty("generationConfig")
    @JsonPropertyDescription("Full generation configuration. Values here override the shorthand fields above.")
    private RagGenerationConfig generationConfig;

    public RagQueryConfig() {
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Float getMinSimilarity() {
        return minSimilarity;
    }

    public void setMinSimilarity(Float minSimilarity) {
        this.minSimilarity = minSimilarity;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Float getTemperature() {
        return temperature;
    }

    public void setTemperature(Float temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public RagRetrievalConfig getRetrievalConfig() {
        return retrievalConfig;
    }

    public void setRetrievalConfig(RagRetrievalConfig retrievalConfig) {
        this.retrievalConfig = retrievalConfig;
    }

    public RagGenerationConfig getGenerationConfig() {
        return generationConfig;
    }

    public void setGenerationConfig(RagGenerationConfig generationConfig) {
        this.generationConfig = generationConfig;
    }
}
