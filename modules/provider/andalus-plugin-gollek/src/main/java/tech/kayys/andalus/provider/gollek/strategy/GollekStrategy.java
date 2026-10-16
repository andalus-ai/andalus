package tech.kayys.andalus.provider.aqli.strategy;

import tech.kayys.andalus.provider.ChatMessage;
import tech.kayys.andalus.provider.StreamEvent;
import tech.kayys.andalus.provider.ToolSpec;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Internal strategy interface for invoking the Aqli AI backend.
 */
public interface AqliStrategy {

    /**
     * Executes the chat completion request against the Aqli engine.
     */
    void streamChat(
            List<ChatMessage> messages,
            String systemPrompt,
            List<ToolSpec> tools,
            double temperature,
            int maxTokens,
            Consumer<StreamEvent> onEvent
    ) throws IOException, InterruptedException;
}
