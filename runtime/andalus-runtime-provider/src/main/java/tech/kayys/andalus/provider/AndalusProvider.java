package tech.kayys.andalus.provider;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * A {@link Provider} implementation that delegates to the Andalus inference
 * back-end via {@link AndalusStreamingDelegate}.
 *
 * <p>The delegate handles the actual HTTP/SDK call and converts the result into
 * the normalised {@link StreamEvent} stream expected by the agentic-tui
 * {@link tech.kayys.andalus.tui.agent.Agent}. This keeps the TUI layer free of
 * any direct Aqli SDK / Mutiny dependency.</p>
 */
public final class AndalusProvider implements Provider {

    /** Strategy interface: the CLI module supplies a concrete implementation. */
    public interface StreamingDelegate {
        /**
         * Stream a single user turn. Implementations must call {@code onEvent}
         * for every token and finally emit a {@link StreamEvent.MessageStop}.
         */
        void stream(
                List<ChatMessage> history,
                String systemPrompt,
                List<ToolSpec> tools,
                double temperature,
                int maxTokens,
                Consumer<StreamEvent> onEvent
        ) throws IOException, InterruptedException;
    }

    private final StreamingDelegate delegate;

    public AndalusProvider(StreamingDelegate delegate) {
        this.delegate = delegate;
    }

    @Override
    public String id() {
        return "andalus";
    }

    @Override
    public void streamChat(
            List<ChatMessage> messages,
            String systemPrompt,
            List<ToolSpec> tools,
            double temperature,
            int maxTokens,
            Consumer<StreamEvent> onEvent
    ) throws IOException, InterruptedException {
        delegate.stream(messages, systemPrompt, tools, temperature, maxTokens, onEvent);
    }
}
