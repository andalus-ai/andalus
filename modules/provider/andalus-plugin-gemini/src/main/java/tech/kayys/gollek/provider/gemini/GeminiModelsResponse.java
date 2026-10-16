package tech.kayys.aqli.provider.gemini;

import com.fasterxml.jackson.annotation.JsonProperty;
import tech.kayys.andalus.inference.ModelInfo;
import java.util.List;

public class GeminiModelsResponse {
    @JsonProperty("models")
    private List<ModelInfo> models;

    public List<ModelInfo> getModels() {
        return models;
    }

    public void setModels(List<ModelInfo> models) {
        this.models = models;
    }
}
