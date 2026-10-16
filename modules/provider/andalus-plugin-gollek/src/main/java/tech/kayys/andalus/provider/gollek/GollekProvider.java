package tech.kayys.andalus.provider.aqli;

import tech.kayys.andalus.provider.ChatMessage;
import tech.kayys.andalus.provider.Provider;
import tech.kayys.andalus.provider.ProviderStrategy;
import tech.kayys.andalus.provider.StreamEvent;
import tech.kayys.andalus.provider.ToolSpec;
import tech.kayys.andalus.provider.aqli.strategy.EmbeddedAqliStrategy;
import tech.kayys.andalus.provider.aqli.strategy.AqliStrategy;
import tech.kayys.andalus.provider.aqli.strategy.GrpcAqliStrategy;
import tech.kayys.andalus.provider.aqli.strategy.RestAqliStrategy;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Andalus Provider implementation for Aqli.
 * Delegates the actual invocation to a {@link AqliStrategy} based on configuration.
 */
public class AqliProvider implements Provider {

    private final AqliStrategy strategy;

    public AqliProvider(ProviderStrategy strategyType, String baseUrl, String apiKey) {
        this.strategy = switch (strategyType) {
            case EMBEDDED -> new EmbeddedAqliStrategy();
            case REST -> new RestAqliStrategy(baseUrl, apiKey);
            case GRPC -> new GrpcAqliStrategy(baseUrl);
            default -> throw new IllegalArgumentException("Unsupported strategy: " + strategyType);
        };
    }

    @Override
    public String id() {
        return "aqli";
    }

    @Override
    public void streamChat(List<ChatMessage> messages, String systemPrompt, List<ToolSpec> tools, double temperature, int maxTokens, Consumer<StreamEvent> onEvent) throws IOException, InterruptedException {
        strategy.streamChat(messages, systemPrompt, tools, temperature, maxTokens, onEvent);
    }
}
