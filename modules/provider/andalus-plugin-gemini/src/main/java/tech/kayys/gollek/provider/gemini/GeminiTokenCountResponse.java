package tech.kayys.aqli.provider.gemini;

import com.fasterxml.jackson.annotation.JsonProperty;

public class GeminiTokenCountResponse {
    @JsonProperty("totalTokens")
    private Integer totalTokens;

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }
}
