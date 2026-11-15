package tech.kayys.aqli.plugin.cloud.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.inference.CompletionRequest;
import tech.kayys.andalus.inference.CompletionResult;
import tech.kayys.andalus.inference.Message;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCloudProviderTest {

    @Test
    void mapsStructuredJsonModeIntoOpenAiRequest() {
        OpenAiCloudProvider provider = new OpenAiCloudProvider();
        CompletionRequest request = CompletionRequest.builder()
                .model("o3")
                .message(Message.user("Return a JSON object."))
                .responseFormat("json_object")
                .build();

        OpenAIRequest mapped = provider.convertToOpenAiRequest(request, false);

        assertNotNull(mapped.getResponseFormat());
        assertEquals("json_object", mapped.getResponseFormat().getType());
        assertFalse(Boolean.TRUE.equals(mapped.getStream()));
    }


    @Test
    void mapsReasoningModelBudgetToTotalCompletionCapAndOmitsUnsupportedSamplingControls() {
        CompletionRequest request = CompletionRequest.builder()
                .model("o3-mini")
                .message(Message.user("Return a JSON object."))
                .parameter("max_tokens", 4096)
                .parameter("temperature", 0.2)
                .parameter("top_p", 1.0)
                .metadata("andalus.reasoningTokenBudget", 75)
                .build();

        OpenAIRequest mapped = new OpenAiCloudProvider().convertToOpenAiRequest(request, false);

        assertNull(mapped.getMaxTokens());
        assertEquals(4171, mapped.getMaxCompletionTokens());
        assertNull(mapped.getTemperature());
        assertNull(mapped.getTopP());
    }

    @Test
    void mapsProviderReportedReasoningTokensToStandardMetadata() throws Exception {
        String responseJson = """
                {
                  "id": "resp-1",
                  "model": "o3",
                  "choices": [],
                  "usage": {
                    "prompt_tokens": 20,
                    "completion_tokens": 50,
                    "total_tokens": 70,
                    "completion_tokens_details": {
                      "reasoning_tokens": 32
                    }
                  }
                }
                """;
        OpenAIResponse response = new ObjectMapper().readValue(responseJson, OpenAIResponse.class);
        CompletionRequest request = CompletionRequest.builder().model("o3").build();

        CompletionResult result = new OpenAiCloudProvider().convertToCompletionResult(response, request);

        assertEquals(32, result.metadata().get("andalus.usage.reasoningTokens"));
        assertEquals("openai-cloud-provider", result.metadata().get("provider"));
        assertEquals(20, result.usage().promptTokens());
        assertEquals(50, result.usage().completionTokens());
    }

    @Test
    void doesNotInventReasoningUsageWhenProviderOmitsIt() throws Exception {
        String responseJson = """
                {
                  "id": "resp-2",
                  "model": "gpt-4.1",
                  "choices": [],
                  "usage": {
                    "prompt_tokens": 20,
                    "completion_tokens": 50,
                    "total_tokens": 70
                  }
                }
                """;
        OpenAIResponse response = new ObjectMapper().readValue(responseJson, OpenAIResponse.class);
        CompletionRequest request = CompletionRequest.builder().model("gpt-4.1").build();

        CompletionResult result = new OpenAiCloudProvider().convertToCompletionResult(response, request);

        assertFalse(result.metadata().containsKey("andalus.usage.reasoningTokens"));
    }
}
