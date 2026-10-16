package tech.kayys.aqli.provider.cerebras;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Cerebras provider adapter for cloud LLM inference.
 */
@ApplicationScoped
public class CerebrasProvider implements InferenceProvider {

    private static final String PROVIDER_ID = "cerebras";
    private static final String PROVIDER_NAME = "Cerebras Cloud";
    private static final String VERSION = "1.0.0";
    private static final Logger log = Logger.getLogger(CerebrasProvider.class);

    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private HttpClient httpClient;
    private volatile boolean initialized = false;

    private final ResourceId stableId = ResourceId.from(
            Id.fromUUID(UUID.nameUUIDFromBytes(PROVIDER_ID.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
            new ResourceType.Plugin()
    );

    @Override
    public ResourceId id() {
        return stableId;
    }

    @Override
    public Metadata metadata() {
        return Metadata.builder()
                .name(PROVIDER_NAME)
                .description("Cerebras - high-performance Llama models inference")
                .version(VERSION)
                .label("vendor", "Cerebras")
                .label("homepage", "https://cerebras.ai")
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
        if (this.objectMapper == null) {
            this.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        }
        if (this.httpClient == null) {
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .proxy(ProxySelector.getDefault())
                    .build();
        }
        this.initialized = true;
        log.info("Cerebras provider initialized");
    }

    private String getApiKey(CompletionRequest request) {
        if (request != null && request.metadata() != null) {
            Object dynamicKey = request.metadata().get("apiKey");
            if (dynamicKey != null) {
                return dynamicKey.toString();
            }
        }
        
        try {
            java.nio.file.Path provConfig = java.nio.file.Paths.get("./config/providers", PROVIDER_ID + ".yaml");
            if (java.nio.file.Files.exists(provConfig)) {
                String content = java.nio.file.Files.readString(provConfig);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("api\\.key:\\s*(.+)").matcher(content);
                if (m.find()) {
                    return m.group(1).trim();
                }
            }
        } catch (Exception e) {
            // ignore
        }

        String sysProp = System.getProperty(PROVIDER_ID + ".api.key");
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp;
        }

        return System.getenv("CEREBRAS_API_KEY");
    }

    @Override
    public Set<String> listModels() {
        return Set.of("llama3.1-8b", "llama3.1-70b");
    }

    @Override
    public ModelInfo getModelInfo(String modelId) {
        return new ModelInfo(modelId, modelId, "cerebras", Set.of("chat"), Map.of(
                "maxContextTokens", 8192,
                "maxOutputTokens", 8192
        ));
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public CompletionResult generate(CompletionRequest request) throws Exception {
        ensureInitialized(request);
        long startTime = System.currentTimeMillis();

        CerebrasRequest cerebrasRequest = buildCerebrasRequest(request);
        String currentApiKey = getApiKey(request);

        if (currentApiKey == null || currentApiKey.isBlank()) {
            throw new RuntimeException("Cerebras API key not configured. Set CEREBRAS_API_KEY environment variable.");
        }

        String url = "https://api.cerebras.ai/v1/chat/completions";
        String body = objectMapper.writeValueAsString(cerebrasRequest);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + currentApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString()).toCompletableFuture().join();
        
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Cerebras failed: " + resp.statusCode() + " " + resp.body());
        }
        
        com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(resp.body());
        CerebrasResponse response = objectMapper.treeToValue(root, CerebrasResponse.class);
        String content = extractContent(root, false);
        long duration = System.currentTimeMillis() - startTime;
        
        Usage usageObj = null;
        if (response != null && response.getUsage() != null) {
            usageObj = new Usage(
                response.getUsage().getPromptTokens(),
                response.getUsage().getCompletionTokens(),
                response.getUsage().getTotalTokens(),
                0.0
            );
        }

        Choice choice = Choice.of(Message.assistant(content), "stop");
        return new CompletionResult(
                request.id() != null ? request.id() : UUID.randomUUID().toString(),
                request.model(),
                List.of(choice),
                usageObj,
                Map.of("provider", PROVIDER_ID),
                duration
        );
    }

    @Override
    public CompletionStream stream(CompletionRequest request) throws Exception {
        ensureInitialized(request);
        CerebrasRequest cerebrasRequest = buildCerebrasRequest(request);
        cerebrasRequest.setStream(true);
        String currentApiKey = getApiKey(request);

        if (currentApiKey == null || currentApiKey.isBlank()) {
            throw new RuntimeException("Cerebras API key not configured. Set CEREBRAS_API_KEY environment variable.");
        }

        String url = "https://api.cerebras.ai/v1/chat/completions";
        String body = objectMapper.writeValueAsString(cerebrasRequest);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + currentApiKey)
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        Multi<CompletionResult> multi = Multi.createFrom().emitter(emitter -> {
            httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                    .thenAccept(resp -> {
                        if (resp.statusCode() != 200) {
                            emitter.fail(new RuntimeException("Cerebras streaming failed: " + resp.statusCode()));
                            return;
                        }
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6).trim();
                                    if (!data.isEmpty() && !"[DONE]".equals(data)) {
                                        try {
                                            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(data);
                                            String content = extractContent(root, true);
                                            if (content != null && !content.isBlank()) {
                                                Choice c = Choice.of(Message.assistant(content), null);
                                                emitter.emit(new CompletionResult(
                                                    request.id() != null ? request.id() : UUID.randomUUID().toString(),
                                                    request.model(),
                                                    List.of(c),
                                                    null,
                                                    Map.of(),
                                                    0
                                                ));
                                            }
                                        } catch (Exception e) {
                                            log.warn("Failed to parse Cerebras chunk: " + data, e);
                                        }
                                    }
                                }
                            }
                            emitter.complete();
                        } catch (Exception e) {
                            emitter.fail(e);
                        }
                    })
                    .exceptionally(t -> {
                        emitter.fail(t);
                        return null;
                    });
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

    private CerebrasRequest buildCerebrasRequest(CompletionRequest request) {
        CerebrasRequest cerebrasRequest = new CerebrasRequest();
        String model = request.model() != null ? request.model().trim() : "llama3.1-8b";
        cerebrasRequest.setModel(model);

        if (request.messages() != null) {
            List<CerebrasMessage> messages = request.messages().stream()
                    .map(msg -> new CerebrasMessage(msg.role().toString().toLowerCase(), msg.content()))
                    .collect(Collectors.toList());
            cerebrasRequest.setMessages(messages);
        }

        Map<String, Object> params = request.parameters();
        if (params != null) {
            if (params.containsKey("temperature")) {
                cerebrasRequest.setTemperature(((Number) params.get("temperature")).doubleValue());
            }
            if (params.containsKey("max_tokens")) {
                cerebrasRequest.setMaxTokens(((Number) params.get("max_tokens")).intValue());
            }
            if (params.containsKey("top_p")) {
                cerebrasRequest.setTopP(((Number) params.get("top_p")).doubleValue());
            }
        }

        return cerebrasRequest;
    }

    private String extractContent(com.fasterxml.jackson.databind.JsonNode root, boolean streaming) {
        if (root == null) return "";
        com.fasterxml.jackson.databind.JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) return "";
        
        com.fasterxml.jackson.databind.JsonNode choice = choices.get(0);
        com.fasterxml.jackson.databind.JsonNode primary = streaming ? choice.path("delta") : choice.path("message");
        com.fasterxml.jackson.databind.JsonNode fallback = streaming ? choice.path("message") : choice.path("delta");

        String content = readContentNode(primary.path("content"));
        if (!content.isBlank()) return content;
        return readContentNode(fallback.path("content"));
    }

    private String readContentNode(com.fasterxml.jackson.databind.JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText("");
        }
        if (contentNode.isObject()) {
            String text = contentNode.path("text").asText("");
            if (!text.isBlank()) {
                return text;
            }
            com.fasterxml.jackson.databind.JsonNode parts = contentNode.path("parts");
            if (parts.isArray()) {
                return readContentNode(parts);
            }
            return "";
        }
        if (contentNode.isArray()) {
            StringJoiner joiner = new StringJoiner("");
            for (com.fasterxml.jackson.databind.JsonNode part : contentNode) {
                if (part == null || part.isNull()) {
                    continue;
                }
                if (part.isTextual()) {
                    joiner.add(part.asText(""));
                    continue;
                }
                String text = part.path("text").asText("");
                if (!text.isBlank()) {
                    joiner.add(text);
                    continue;
                }
                String nested = readContentNode(part.path("content"));
                if (!nested.isBlank()) {
                    joiner.add(nested);
                }
            }
            return joiner.toString();
        }

        return "";
    }
}
