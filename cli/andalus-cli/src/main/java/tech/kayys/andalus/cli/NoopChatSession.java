package tech.kayys.andalus.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import tech.kayys.andalus.provider.ChatMessage;
import tech.kayys.andalus.agent.AndalusSessionPersistence;

/**
 * Minimal in-process chat session used when Aqli SDK is not available.
 * Supports the subset of methods used by the CLI: getModelId/getProviderId,
 * reset, addMessage, getHistory, setSystemPrompt and a simple streaming API.
 * Now includes session persistence.
 */
public final class NoopChatSession {
    private String modelId;
    private String providerId;
    private final boolean memory;
    private final List<ChatMessage> history = new ArrayList<>();
    private Object systemPrompt;
    private final AndalusSessionPersistence persistence = new AndalusSessionPersistence();

    public NoopChatSession(String modelId, String providerId, boolean memory) {
        this.modelId = modelId == null ? "unknown-model" : modelId;
        this.providerId = providerId;
        this.memory = memory;
        // Load persisted history if available
        try {
            List<ChatMessage> loaded = persistence.load();
            if (loaded != null && !loaded.isEmpty()) {
                history.addAll(loaded);
            }
        } catch (Exception e) {
            System.err.println("[NoopChatSession] Failed to load persisted history: " + e.getMessage());
        }
    }

    public void reset() { 
        history.clear();
        try {
            persistence.save(history);
        } catch (Exception e) {
            System.err.println("[NoopChatSession] Failed to save after reset: " + e.getMessage());
        }
    }
    
    public void addMessage(Object m) { 
        if (m == null) return;
        try {
            if (m instanceof ChatMessage cm) {
                history.add(cm);
                persistence.save(history);
            } else if (m instanceof String s) {
                ChatMessage cm = ChatMessage.userText(s);
                history.add(cm);
                persistence.save(history);
            } else {
                ChatMessage cm = ChatMessage.userText(m.toString());
                history.add(cm);
                persistence.save(history);
            }
        } catch (Exception e) {
            System.err.println("[NoopChatSession] Failed in addMessage: " + e.getMessage());
            // still add to memory even if persistence fails
            if (m != null) history.add(m instanceof ChatMessage ? (ChatMessage) m : ChatMessage.userText(m.toString()));
        }
    }
    
    public List<ChatMessage> getHistory() { 
        // Convert to ChatMessage list for consistency
        List<ChatMessage> result = new ArrayList<>();
        for (Object item : history) {
            if (item instanceof ChatMessage cm) {
                result.add(cm);
            } else if (item instanceof String s) {
                result.add(ChatMessage.userText(s));
            }
        }
        return List.copyOf(result);
    }
    
    public String getProviderId() { return providerId; }
    public String getModelId() { return modelId; }
    public void setSystemPrompt(Object prompt) { this.systemPrompt = prompt; }

    public Stream stream(String userPrompt) {
        return new Stream(userPrompt, this);
    }

    public static final class Chunk {
        private final String delta;
        public Chunk(String delta) { this.delta = delta; }
        public String getDelta() { return delta; }
    }

    public static final class Stream {
        private final String prompt;
        private final NoopChatSession session;
        public Stream(String prompt, NoopChatSession s) { this.prompt = prompt; this.session = s; }
        public StreamSubscriber subscribe() { return new StreamSubscriber(prompt, session); }
    }

    public static final class StreamSubscriber {
        private final String prompt;
        private final NoopChatSession session;
        public StreamSubscriber(String prompt, NoopChatSession s) { this.prompt = prompt; this.session = s; }
        public <T> void with(Consumer<Chunk> onItem, Consumer<Throwable> onFailure, Runnable onComplete) {
            try {
                // Add user message first
                if (prompt != null && !prompt.isBlank()) {
                    session.addMessage(ChatMessage.userText(prompt));
                }
                
                // Simulated assistant response that mimics normal assistant format.
                String base = session.getModelId() == null ? "simulated-model" : session.getModelId();
                String replyText = "(simulated assistant — model=" + base + ") I can help with: " + (prompt == null ? "<empty>" : prompt);
                
                // Add assistant message
                ChatMessage assistantMsg = ChatMessage.assistantText(replyText);
                session.addMessage(assistantMsg);
                
                if (onItem != null) onItem.accept(new Chunk(replyText));
                if (onComplete != null) onComplete.run();
            } catch (Throwable t) {
                if (onFailure != null) onFailure.accept(t);
            }
        }
    }
}
