package tech.kayys.andalus.rag.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;
import java.util.Map;

/**
 * Strongly-typed DTO for RAG generation (LLM) configuration.
 *
 * Mirrors the fields in {@code tech.kayys.andalus.rag.core.GenerationConfig}.
 */
public class RagGenerationConfig {

    @JsonProperty("provider")
    @JsonPropertyDescription("LLM provider to use for response generation. E.g. aqli, openai, anthropic. Defaults to 'aqli'.")
    private String provider = "aqli";

    @JsonProperty("model")
    @JsonPropertyDescription("Model identifier to use for generation. E.g. Qwen/Qwen2.5-0.5B-Instruct. Defaults to 'Qwen/Qwen2.5-0.5B-Instruct'.")
    private String model = "Qwen/Qwen2.5-0.5B-Instruct";

    @JsonProperty("temperature")
    @JsonPropertyDescription("Sampling temperature (0.0–2.0). Lower values give more deterministic output. Defaults to 0.7.")
    private Float temperature = 0.7f;

    @JsonProperty("maxTokens")
    @JsonPropertyDescription("Maximum number of tokens to generate in the response. Defaults to 1024.")
    private Integer maxTokens = 1024;

    @JsonProperty("topP")
    @JsonPropertyDescription("Nucleus sampling probability mass (0.0–1.0). Defaults to 1.0.")
    private Float topP = 1.0f;

    @JsonProperty("frequencyPenalty")
    @JsonPropertyDescription("Penalty for token frequency in the output (-2.0–2.0). Defaults to 0.0.")
    private Float frequencyPenalty = 0.0f;

    @JsonProperty("presencePenalty")
    @JsonPropertyDescription("Penalty for new topics / token presence (-2.0–2.0). Defaults to 0.0.")
    private Float presencePenalty = 0.0f;

    @JsonProperty("stopSequences")
    @JsonPropertyDescription("List of token sequences that stop generation when encountered.")
    private List<String> stopSequences;

    @JsonProperty("systemPrompt")
    @JsonPropertyDescription("System-level instruction prepended to every generation. Defaults to 'You are a helpful assistant.'.")
    private String systemPrompt = "You are a helpful assistant.";

    @JsonProperty("additionalParams")
    @JsonPropertyDescription("Provider-specific extra parameters forwarded verbatim.")
    private Map<String, Object> additionalParams;

    @JsonProperty("enableCitations")
    @JsonPropertyDescription("Whether to include source citations in the generated response. Defaults to false.")
    private Boolean enableCitations = false;

    @JsonProperty("enableGrounding")
    @JsonPropertyDescription("Whether to enable grounding / factuality verification. Defaults to false.")
    private Boolean enableGrounding = false;

    @JsonProperty("citationStyle")
    @JsonPropertyDescription("Style of citations to use when enableCitations is true. E.g. INLINE_NUMBERED, FOOTNOTE, APA.")
    private String citationStyle = "INLINE_NUMBERED";

    @JsonProperty("enableFactualityChecks")
    @JsonPropertyDescription("Whether to run factuality checks on the generated response. Defaults to false.")
    private Boolean enableFactualityChecks = false;

    @JsonProperty("enableBiasDetection")
    @JsonPropertyDescription("Whether to run bias detection on the generated response. Defaults to false.")
    private Boolean enableBiasDetection = false;

    @JsonProperty("safetySettings")
    @JsonPropertyDescription("Provider-specific safety settings (e.g. harm categories and thresholds).")
    private Map<String, Object> safetySettings;

    public RagGenerationConfig() {
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

    public Float getTopP() {
        return topP;
    }

    public void setTopP(Float topP) {
        this.topP = topP;
    }

    public Float getFrequencyPenalty() {
        return frequencyPenalty;
    }

    public void setFrequencyPenalty(Float frequencyPenalty) {
        this.frequencyPenalty = frequencyPenalty;
    }

    public Float getPresencePenalty() {
        return presencePenalty;
    }

    public void setPresencePenalty(Float presencePenalty) {
        this.presencePenalty = presencePenalty;
    }

    public List<String> getStopSequences() {
        return stopSequences;
    }

    public void setStopSequences(List<String> stopSequences) {
        this.stopSequences = stopSequences;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public Map<String, Object> getAdditionalParams() {
        return additionalParams;
    }

    public void setAdditionalParams(Map<String, Object> additionalParams) {
        this.additionalParams = additionalParams;
    }

    public Boolean getEnableCitations() {
        return enableCitations;
    }

    public void setEnableCitations(Boolean enableCitations) {
        this.enableCitations = enableCitations;
    }

    public Boolean getEnableGrounding() {
        return enableGrounding;
    }

    public void setEnableGrounding(Boolean enableGrounding) {
        this.enableGrounding = enableGrounding;
    }

    public String getCitationStyle() {
        return citationStyle;
    }

    public void setCitationStyle(String citationStyle) {
        this.citationStyle = citationStyle;
    }

    public Boolean getEnableFactualityChecks() {
        return enableFactualityChecks;
    }

    public void setEnableFactualityChecks(Boolean enableFactualityChecks) {
        this.enableFactualityChecks = enableFactualityChecks;
    }

    public Boolean getEnableBiasDetection() {
        return enableBiasDetection;
    }

    public void setEnableBiasDetection(Boolean enableBiasDetection) {
        this.enableBiasDetection = enableBiasDetection;
    }

    public Map<String, Object> getSafetySettings() {
        return safetySettings;
    }

    public void setSafetySettings(Map<String, Object> safetySettings) {
        this.safetySettings = safetySettings;
    }
}
