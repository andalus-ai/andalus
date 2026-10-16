/*
 * MIT License
 *
 * Copyright (c) 2026 Kayys.tech
 */

package tech.kayys.aqli.plugin.cloud.openai;

import tech.kayys.andalus.extension.Id;
import tech.kayys.andalus.extension.Metadata;
import tech.kayys.andalus.extension.Version;
import tech.kayys.andalus.identity.ResourceId;
import tech.kayys.andalus.inference.Choice;
import tech.kayys.andalus.inference.CompletionRequest;
import tech.kayys.andalus.inference.CompletionResult;
import tech.kayys.andalus.inference.CompletionStream;
import tech.kayys.andalus.inference.InferenceProvider;
import tech.kayys.andalus.inference.Message;
import tech.kayys.andalus.inference.MessageRole;
import tech.kayys.andalus.inference.ModelInfo;
import tech.kayys.andalus.inference.Usage;
import tech.kayys.andalus.resource.ResourceType;
import tech.kayys.andalus.embedding.EmbeddingRequest;
import tech.kayys.andalus.embedding.EmbeddingResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class OpenAiCloudProvider implements InferenceProvider {
    private static final Logger LOG = Logger.getLogger(OpenAiCloudProvider.class);
    public static final String ID = "openai-cloud-provider";
    public static final String VERSION = "1.0.0";
    
    private volatile OpenAiConfig openAiConfig;
    private volatile OpenAIClient client;
    private volatile boolean initialized = false;
    private final Map<String, Object> config = new ConcurrentHashMap<>();

    private List<String> getSupportedModels() {
        return tech.kayys.andalus.provider.ModelsDevRegistry.getInstance().getModelsForProvider("openai");
    }

    private final ResourceId stableId = ResourceId.from(
            Id.fromUUID(UUID.nameUUIDFromBytes(ID.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
            new ResourceType.Plugin()
    );

    @Override
    public ResourceId id() {
        return stableId;
    }

    @Override
    public Metadata metadata() {
        return Metadata.builder()
                .name("OpenAI")
                .description("OpenAI - GPT-4, GPT-3.5-Turbo and compatible APIs")
                .version(VERSION)
                .label("vendor", "OpenAI Inc.")
                .label("homepage", "https://openai.com")
                .build();
    }

    @Override
    public ResourceType type() {
        return new ResourceType.Plugin();
    }

    @Override
    public void initialize() throws Exception {
        ensureInitialized(null);
    }

    private synchronized void ensureInitialized(CompletionRequest request) {
        if (initialized) return;
        
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null) {
            apiKey = System.getProperty("openai.api.key");
        }
        
        if (request != null && request.metadata() != null) {
            Object dynamicKey = request.metadata().get("apiKey");
            if (dynamicKey != null) {
                apiKey = dynamicKey.toString();
            }
        }
        
        if (apiKey == null) {
            try {
                java.nio.file.Path provConfig = java.nio.file.Paths.get("./config/providers", ID + ".yaml");
                if (java.nio.file.Files.exists(provConfig)) {
                    String content = java.nio.file.Files.readString(provConfig);
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("api\\.key:\\s*(.+)").matcher(content);
                    if (m.find()) {
                        apiKey = m.group(1).trim();
                    }
                }
            } catch (Exception e) {}
        }
        
        if (apiKey != null) {
            this.config.put("apiKey", apiKey);
        }
        
        this.openAiConfig = new OpenAiConfig(
                (String) this.config.get("apiKey"),
                (String) this.config.getOrDefault("baseUrl", "https://api.openai.com/v1"),
                (String) this.config.get("organization"),
                (Boolean) this.config.getOrDefault("enabled", true),
                (Integer) this.config.getOrDefault("timeoutSeconds", 30),
                (Integer) this.config.getOrDefault("maxRetries", 3)
        );

        if (openAiConfig.isValid()) {
            this.client = new OpenAIClient(openAiConfig);
            this.initialized = true;
            LOG.infof("OpenAI Cloud Provider initialized (version %s)", VERSION);
        }
    }

    @Override
    public void shutdown() throws Exception {
        initialized = false;
        config.clear();
        openAiConfig = null;
        client = null;
        LOG.info("OpenAI provider shutdown complete");
    }

    @Override
    public Set<String> listModels() {
        return new HashSet<>(getSupportedModels());
    }

    @Override
    public ModelInfo getModelInfo(String modelId) {
        return new ModelInfo(modelId, modelId, "openai", Set.of("chat", "embeddings"), Map.of(
                "maxContextTokens", 128000,
                "maxOutputTokens", 4096
        ));
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public CompletionResult generate(CompletionRequest request) throws Exception {
        ensureInitialized(request);
        if (!initialized || openAiConfig == null || !openAiConfig.isValid()) {
            throw new RuntimeException("OpenAI API key not configured");
        }

        OpenAIRequest openAiRequest = convertToOpenAiRequest(request, false);
        LOG.debugf("Processing completion request for model: %s", request.model());

        OpenAIResponse response = client.chatCompletions(openAiRequest)
            .await().atMost(java.time.Duration.ofSeconds(openAiConfig.timeoutSeconds()));
            
        return convertToCompletionResult(response, request);
    }

    @Override
    public CompletionStream stream(CompletionRequest request) throws Exception {
        ensureInitialized(request);
        if (!initialized || openAiConfig == null || !openAiConfig.isValid()) {
            throw new RuntimeException("OpenAI API key not configured");
        }

        OpenAIRequest openAiRequest = convertToOpenAiRequest(request, true);
        LOG.debugf("Processing streaming request for model: %s", request.model());

        Multi<CompletionResult> multi = client.chatCompletionsStream(openAiRequest)
            .onItem().transformToMultiAndConcatenate(response -> {
                if (response.getChoices() == null || response.getChoices().isEmpty()) {
                    return Multi.createFrom().empty();
                }
                
                OpenAIStreamChoice choice = response.getChoices().get(0);
                String content = extractContent(choice);
                if (content == null || content.isBlank()) {
                    return Multi.createFrom().empty();
                }
                
                Message msg = Message.assistant(content);
                Choice c = Choice.of(msg, choice.getFinishReason() != null ? mapFinishReason(choice.getFinishReason()) : null);
                
                return Multi.createFrom().item(new CompletionResult(
                    request.id() != null ? request.id() : UUID.randomUUID().toString(),
                    request.model(),
                    List.of(c),
                    null,
                    Map.of(),
                    0
                ));
            });
            
        Iterator<CompletionResult> iterator = multi.subscribe().asIterable().iterator();
        
        return new CompletionStream() {
            private boolean complete = false;
            
            @Override
            public boolean hasNext() {
                boolean hasNext = iterator.hasNext();
                if (!hasNext) complete = true;
                return hasNext;
            }
            
            @Override
            public CompletionResult next() {
                return iterator.next();
            }
            
            @Override
            public void close() throws Exception {
            }
            
            @Override
            public boolean isComplete() {
                return complete;
            }
            
            @Override
            public String getStreamId() {
                return request.id();
            }
        };
    }

    private OpenAIRequest convertToOpenAiRequest(CompletionRequest request, boolean streaming) {
        OpenAIRequest openAiRequest = new OpenAIRequest();
        openAiRequest.setModel(request.model());
        
        if (request.messages() != null) {
            List<OpenAIMessage> messages = request.messages().stream()
                    .map(this::convertToOpenAiMessage)
                    .collect(Collectors.toList());
            openAiRequest.setMessages(messages);
        }
        
        Map<String, Object> params = request.parameters();
        if (params != null) {
            if (params.containsKey("temperature")) {
                openAiRequest.setTemperature(((Number) params.get("temperature")).doubleValue());
            }
            if (params.containsKey("max_tokens")) {
                openAiRequest.setMaxTokens(((Number) params.get("max_tokens")).intValue());
            }
            if (params.containsKey("top_p")) {
                openAiRequest.setTopP(((Number) params.get("top_p")).doubleValue());
            }
        } else {
            openAiRequest.setTemperature(request.temperature());
            openAiRequest.setMaxTokens(request.maxTokens());
            openAiRequest.setTopP(request.topP());
        }
        
        if (request.stopSequences() != null && !request.stopSequences().isEmpty()) {
            openAiRequest.setStop(request.stopSequences());
        }
        
        openAiRequest.setStream(streaming);
        return openAiRequest;
    }

    private OpenAIMessage convertToOpenAiMessage(Message message) {
        OpenAIMessage openAiMessage = new OpenAIMessage();
        openAiMessage.setRole(message.role().toString().toLowerCase());
        openAiMessage.setContent(message.content());
        return openAiMessage;
    }

    private CompletionResult convertToCompletionResult(OpenAIResponse response, CompletionRequest request) {
        List<Choice> resultChoices = new ArrayList<>();
        
        if (response.getChoices() != null) {
            for (OpenAIResponse.OpenAIChoice oc : response.getChoices()) {
                String content = oc.getMessage() != null ? oc.getMessage().getContent() : "";
                Message msg = Message.assistant(content);
                resultChoices.add(new Choice(
                    oc.getIndex() != null ? oc.getIndex() : 0, 
                    msg, 
                    oc.getFinishReason() != null ? mapFinishReason(oc.getFinishReason()) : "stop", 
                    Map.of()
                ));
            }
        }
        
        Usage usage = null;
        if (response.getUsage() != null) {
            OpenAIUsage u = response.getUsage();
            usage = new Usage(
                u.getPromptTokens() != null ? u.getPromptTokens() : 0,
                u.getCompletionTokens() != null ? u.getCompletionTokens() : 0,
                u.getTotalTokens() != null ? u.getTotalTokens() : 0,
                0.0
            );
        }
        
        return new CompletionResult(
            request.id() != null ? request.id() : UUID.randomUUID().toString(),
            response.getModel() != null ? response.getModel() : request.model(),
            resultChoices,
            usage,
            Map.of("provider", ID),
            0
        );
    }

    private String extractContent(OpenAIStreamChoice choice) {
        OpenAIMessage delta = choice.getDelta();
        if (delta != null && delta.getContent() != null) {
            return delta.getContent();
        }
        OpenAIMessage message = choice.getMessage();
        if (message != null && message.getContent() != null) {
            return message.getContent();
        }
        return "";
    }

    private String mapFinishReason(String openAiFinishReason) {
        if (openAiFinishReason == null) return "stop";
        return switch (openAiFinishReason.toLowerCase()) {
            case "stop" -> "stop";
            case "length" -> "length";
            case "tool_calls", "function_call" -> "tool_calls";
            default -> "error";
        };
    }

    public float[] embed(String text) {
        ensureInitialized(null);
        if (!initialized) {
            throw new RuntimeException("Provider not initialized");
        }
        OpenAIEmbeddingRequest request = new OpenAIEmbeddingRequest("text-embedding-3-small", text);
        request.setEncodingFormat("float");
        return client.embeddings(request)
                .map(response -> {
                    if (response.getData() == null || response.getData().isEmpty()) {
                        throw new RuntimeException("No embeddings returned from OpenAI");
                    }
                    List<Double> embedding = response.getData().get(0).getEmbedding();
                    float[] result = new float[embedding.size()];
                    for (int i = 0; i < embedding.size(); i++) {
                        result[i] = embedding.get(i).floatValue();
                    }
                    return result;
                })
                .await().atMost(java.time.Duration.ofSeconds(30));
    }

    public float[][] embedBatch(String[] texts) {
        ensureInitialized(null);
        if (!initialized) {
            throw new RuntimeException("Provider not initialized");
        }
        OpenAIEmbeddingRequest request = new OpenAIEmbeddingRequest("text-embedding-3-small", List.of(texts));
        request.setEncodingFormat("float");
        return client.embeddings(request)
                .map(response -> {
                    if (response.getData() == null || response.getData().isEmpty()) {
                        throw new RuntimeException("No embeddings returned from OpenAI");
                    }
                    float[][] embeddings = new float[response.getData().size()][];
                    for (int i = 0; i < response.getData().size(); i++) {
                        List<Double> embedding = response.getData().get(i).getEmbedding();
                        embeddings[i] = new float[embedding.size()];
                        for (int j = 0; j < embedding.size(); j++) {
                            embeddings[i][j] = embedding.get(j).floatValue();
                        }
                    }
                    return embeddings;
                })
                .await().atMost(java.time.Duration.ofSeconds(60));
    }

    public int dimension() {
        return 1536;
    }

    public EmbeddingResponse generateEmbeddings(List<String> inputs, String model, Integer dimensions) {
        ensureInitialized(null);
        if (!initialized) {
            throw new RuntimeException("Provider not initialized");
        }
        OpenAIEmbeddingRequest request = new OpenAIEmbeddingRequest(model, inputs);
        request.setEncodingFormat("float");
        if (dimensions != null) {
            request.setDimensions(dimensions);
        }
        return client.embeddings(request)
                .map(response -> {
                    if (response.getData() == null || response.getData().isEmpty()) {
                        throw new RuntimeException("No embeddings returned from OpenAI");
                    }
                    List<float[]> embeddings = new ArrayList<>();
                    for (OpenAIEmbeddingData data : response.getData()) {
                        List<Double> embedding = data.getEmbedding();
                        float[] floatEmbedding = new float[embedding.size()];
                        for (int i = 0; i < embedding.size(); i++) {
                            floatEmbedding[i] = embedding.get(i).floatValue();
                        }
                        embeddings.add(floatEmbedding);
                    }
                    int dim = embeddings.isEmpty() ? 0 : embeddings.get(0).length;
                    return new EmbeddingResponse(
                            embeddings,
                            dim,
                            ID,
                            response.getModel() != null ? response.getModel() : model
                    );
                })
                .await().atMost(java.time.Duration.ofSeconds(60));
    }
}
