package tech.kayys.andalus.agent.backend.aqli;

import org.junit.jupiter.api.Test;
import tech.kayys.aqli.sdk.core.AqliSdk;
import tech.kayys.andalus.agent.spi.InferenceRequest;
import tech.kayys.andalus.agent.spi.InferenceTypes.ToolDefinition;
import tech.kayys.andalus.agent.spi.InferenceTypes.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AqliBackendAdapterTest {

    @Test
    void mapsAndalusInferenceRequestThroughCurrentAqliSdk() throws Exception {
        AqliSdk sdk = mock(AqliSdk.class);
        when(sdk.listAvailableProviders()).thenReturn(List.of());
        when(sdk.createCompletionAsync(any())).thenAnswer(invocation -> {
            tech.kayys.aqli.spi.inference.InferenceRequest request = invocation.getArgument(0);

            assertThat(request.getRequestId()).isEqualTo("request-1");
            assertThat(request.getMessages()).hasSize(1);
            assertThat(request.getMessages().getFirst().getContent()).isEqualTo("hello");
            assertThat(request.getTools()).hasSize(1);
            assertThat(request.getTools().getFirst().getName()).isEqualTo("lookup");

            return CompletableFuture.completedFuture(
                    tech.kayys.aqli.spi.inference.InferenceResponse.builder()
                            .requestId("request-1")
                            .model("test-model")
                            .content("done")
                            .inputTokens(3)
                            .outputTokens(2)
                            .durationMs(42)
                            .build());
        });

        AqliBackendAdapter adapter = new AqliBackendAdapter(sdk);
        InferenceRequest request = InferenceRequest.builder()
                .requestId("request-1")
                .model("test-model")
                .message(new UserMessage("hello"))
                .tool(new ToolDefinition(
                        "lookup",
                        "Lookup a value",
                        Map.of("type", "object")))
                .build();

        tech.kayys.andalus.agent.spi.InferenceResponse response =
                adapter.infer(request).await().indefinitely();

        assertThat(response.requestId()).isEqualTo("request-1");
        assertThat(response.model()).isEqualTo("test-model");
        assertThat(response.message().content()).isEqualTo("done");
        assertThat(response.usage().totalTokens()).isEqualTo(5);
        assertThat(response.durationMs()).isEqualTo(42);
    }
}
