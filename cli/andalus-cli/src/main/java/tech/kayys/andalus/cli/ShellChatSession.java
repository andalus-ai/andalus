package tech.kayys.andalus.cli;

import tech.kayys.andalus.inference.AndalusInferenceService;
import tech.kayys.andalus.inference.AndalusInferenceServiceFactory;
import tech.kayys.aqli.spi.inference.StreamingInferenceChunk;
import tech.kayys.andalus.provider.ChatMessage;
import tech.kayys.andalus.agent.AndalusSessionPersistence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Chat session that delegates to aqli subprocess via AndalusInferenceService.
 * No SDK initialization - just subprocess fallback directly.
 */
public final class ShellChatSession {
    
    private final String modelId;
    private final String providerId;
    private final boolean memory;
    private final List<ChatMessage> history = new ArrayList<>();
    private final AndalusSessionPersistence persistence = new AndalusSessionPersistence();
    private String systemPrompt;
    private AndalusInferenceService inferenceService;
    private boolean sdkInitialized = false;

    public ShellChatSession(String modelId, String providerId, boolean memory) {
        this.modelId = extractModelId(modelId);
        this.providerId = providerId;
        this.memory = memory;
        this.systemPrompt = "You are a helpful general-purpose AI assistant.";
        // Load persisted history if available
        try {
            List<ChatMessage> loaded = persistence.load();
            if (loaded != null && !loaded.isEmpty()) {
                history.addAll(loaded);
            }
        } catch (Exception e) {
            System.err.println("[ShellChatSession] Failed to load persisted history: " + e.getMessage());
        }
        // SDK is lazily initialized on first use
    }
    
    /**
     * Extract model ID string from a potential ModelInfo object.
     * Handles both String model IDs and ModelInfo objects.
     */
    private static String extractModelId(String modelIdObj) {
        if (modelIdObj == null || modelIdObj.isBlank()) {
            return null;
        }
        
        String modelIdStr = modelIdObj;
        
        // If it looks like a ModelInfo object, extract the modelId value
        if (modelIdStr.startsWith("ModelInfo{") && modelIdStr.contains("modelId='")) {
            int start = modelIdStr.indexOf("modelId='") + "modelId='".length();
            int end = modelIdStr.indexOf("'", start);
            if (end > start) {
                return modelIdStr.substring(start, end);
            }
        }
        
        // Otherwise return as-is (it's already a plain model ID)
        return modelIdStr;
    }

    public void reset() { 
        history.clear();
        if (inferenceService != null) {
            // inferenceService.clearHistory();
        }
    }
    
    public void addMessage(Object m) {
        if (m == null) return;
        try {
            Files.write(Path.of(System.getProperty("user.home"), ".andalus", "sessions", "addMessage.log"),
                    ("[addMessage] " + (m instanceof ChatMessage ? "ChatMessage" : m.getClass().getSimpleName()) + "\n").getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) { }
        
        if (m instanceof ChatMessage cm) {
            history.add(cm);
            // persist recent history after each addition
            persistence.save(history);
        } else if (m instanceof String s) {
            // convenience: treat plain strings as user text
            ChatMessage cm = ChatMessage.userText(s);
            history.add(cm);
            persistence.save(history);
        } else {
            // ignore unknown types for persistence but keep in-memory as text wrapper
            try {
                ChatMessage cm = ChatMessage.userText(m.toString());
                history.add(cm);
                persistence.save(history);
            } catch (Exception ignored) {}
        }
    }
    
    public List<ChatMessage> getHistory() {
        return List.copyOf(history);
    }
    
    public String getProviderId() { 
        return providerId; 
    }
    
    public String getModelId() { 
        return modelId; 
    }
    
    public void setSystemPrompt(String prompt) { 
        this.systemPrompt = prompt != null ? prompt : "You are a helpful general-purpose AI assistant.";
    }

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
        private final ShellChatSession session;
        
        public Stream(String prompt, ShellChatSession s) { 
            this.prompt = prompt; 
            this.session = s; 
        }
        
        public StreamSubscriber subscribe() { 
            return new StreamSubscriber(prompt, session); 
        }
    }

    public static final class StreamSubscriber {
        private final String prompt;
        private final ShellChatSession session;
        
        public StreamSubscriber(String prompt, ShellChatSession s) { 
            this.prompt = prompt; 
            this.session = s; 
        }
        
        public <T> void with(Consumer<Chunk> onItem, Consumer<Throwable> onFailure, Runnable onComplete) {
            try {
                // Skip SDK; go directly to subprocess fallback via AndalusInferenceService with null SDK
                tech.kayys.andalus.inference.AndalusInferenceService fallback = tech.kayys.andalus.inference.AndalusInferenceServiceFactory.create(null, session.systemPrompt, session.modelId);
                
                // Persist the user's message immediately
                session.addMessage(ChatMessage.userText(prompt));

                StringBuilder assistantAccumulator = new StringBuilder();

                fallback.inferenceStreaming(session.modelId, session.systemPrompt, java.util.List.of(tech.kayys.aqli.spi.Message.user(prompt)), java.util.List.of(), tech.kayys.aqli.sdk.core.ChatParams.of(0.7, 4096))
                    .subscribe()
                    .with(
                        chunk -> {
                            if (chunk != null && chunk.delta() != null && onItem != null) {
                                String d = chunk.delta();
                                assistantAccumulator.append(d);
                                onItem.accept(new Chunk(d));
                            }
                        },
                        error -> {
                            if (onFailure != null) {
                                onFailure.accept(error);
                            }
                        },
                        () -> {
                            // On complete, persist assistant response as a single ChatMessage
                            try {
                                String assistantText = assistantAccumulator.toString();
                                if (assistantText != null && !assistantText.isBlank()) {
                                    session.addMessage(ChatMessage.assistantText(assistantText));
                                }
                            } catch (Exception ignored) {}

                            if (onComplete != null) {
                                onComplete.run();
                            }
                        }
                    );
                
            } catch (Exception e) {
                if (onFailure != null) {
                    onFailure.accept(new RuntimeException("Inference failed: " + e.getMessage(), e));
                }
            }
        }
        
        private void initializeSDKWithTimeout() {
            if (session.sdkInitialized) return;
            
            session.sdkInitialized = true;
            
            // Run initialization in a separate thread with timeout
            Thread initThread = new Thread(() -> {
                try {
                    System.err.println("[ShellChatSession] Initializing SDK in background...");
                    session.inferenceService = AndalusInferenceServiceFactory.create(
                        session.systemPrompt, 
                        session.modelId
                    );
                    System.err.println("[ShellChatSession] Service initialized (SDK or fallback)");
                } catch (Exception e) {
                    System.err.println("[ShellChatSession] Service init failed: " + e.getMessage());
                    session.inferenceService = null;
                }
            }, "AndalusSDKInit");
            
            initThread.setDaemon(true);
            initThread.start();
            
            // Wait up to 5 seconds for initialization
            try {
                initThread.join(5000);
                if (initThread.isAlive()) {
                    System.err.println("[ShellChatSession] Service init timed out after 5s, continuing...");
                }
            } catch (InterruptedException e) {
                System.err.println("[ShellChatSession] Interrupted waiting for service init");
            }
        }
        
        private void handleFallback(Consumer<Chunk> onItem, Consumer<Throwable> onFailure, Runnable onComplete) {
            try {
                String fallback = "(simulated — Aqli SDK unavailable) Helpful response...";
                
                if (onItem != null) {
                    onItem.accept(new Chunk(fallback));
                }
                if (onComplete != null) {
                    onComplete.run();
                }
            } catch (Exception e) {
                if (onFailure != null) {
                    onFailure.accept(e);
                }
            }
        }
    }
}
