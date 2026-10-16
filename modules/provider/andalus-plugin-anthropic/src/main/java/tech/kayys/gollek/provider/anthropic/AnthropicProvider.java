package tech.kayys.aqli.provider.anthropic;

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
import tech.kayys.andalus.inference.ModelInfo;
import tech.kayys.andalus.inference.Usage;
import tech.kayys.andalus.resource.ResourceType;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@ApplicationScoped
public class AnthropicProvider implements InferenceProvider {

    private static final String PROVIDER_ID = "anthropic";
    private static final String PROVIDER_NAME = "Anthropic";
    private static final String VERSION = "1.0.0";
    private static final String API_VERSION = "2023-06-01";
    private static final Logger log = Logger.getLogger(AnthropicProvider.class);

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Inject
    jakarta.enterprise.inject.Instance<AnthropicConfig> configDetailsInstance;

    private AnthropicConfig configDetails;
    private HttpClient httpClient;
    private boolean initialized = false;

    @jakarta.annotation.PostConstruct
    void init() {
        if (configDetailsInstance != null && configDetailsInstance.isResolvable()) {
            configDetails = configDetailsInstance.get();
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(ProxySelector.getDefault())
                .build();
    }

    private final AtomicInteger requestCounter = new AtomicInteger(0);

    private final ResourceId stableId = ResourceId.from(
            Id.fromUUID(UUID.nameUUIDFromBytes(PROVIDER_ID.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
            new ResourceType.Plugin()
    );

    @Override
    public ResourceId id() {
        return stableId;
    }

    @Override
    public ResourceType type() {
        return new ResourceType.Plugin();
    }

    @Override
    public Metadata metadata() {
        return Metadata.builder()
                .name(PROVIDER_NAME)
                .description("Anthropic - Claude family of models")
                .version(VERSION)
                .label("vendor", "Anthropic")
                .label("homepage", "https://anthropic.com")
                .build();
    }

    @Override
    public void initialize() throws Exception {
        log.info("Anthropic provider initialized");
        if (this.objectMapper == null) {
            this.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        }
        if (this.httpClient == null) {
            this.httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .proxy(java.net.ProxySelector.getDefault())
                .build();
        }
        this.initialized = true;
    }

    @Override
    public void shutdown() throws Exception {
        log.info("Anthropic provider shutting down");
        this.initialized = false;
    }

    @Override
    public Set<String> listModels() {
        return new java.util.HashSet<>(tech.kayys.andalus.provider.ModelsDevRegistry.getInstance().getModelsForProvider("anthropic"));
    }

    @Override
    public ModelInfo getModelInfo(String modelId) {
        return new ModelInfo(modelId, modelId, "anthropic", Set.of("chat"), Map.of(
                "maxContextTokens", 200000,
                "maxOutputTokens", 8192
        ));
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public CompletionResult generate(CompletionRequest request) throws Exception {
        if (!initialized) initialize();

        long startTime = System.currentTimeMillis();
        AnthropicRequest anthropicRequest = buildAnthropicRequest(request);
        String currentApiKey = getApiKey(request);

        if (currentApiKey == null || currentApiKey.isBlank()) {
            throw new RuntimeException("Anthropic API key not configured. Set ANTHROPIC_API_KEY environment variable.");
        }

        String baseUrl = (configDetails != null && configDetails.baseUrl() != null) ? configDetails.baseUrl() : "https://api.anthropic.com";
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String url = baseUrl + "/v1/messages";

        String body = objectMapper.writeValueAsString(anthropicRequest);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-api-key", currentApiKey)
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            throw new RuntimeException("Anthropic failed: " + resp.statusCode() + " " + resp.body());
        }

        com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(resp.body());
        AnthropicResponse response = objectMapper.treeToValue(root, AnthropicResponse.class);
        
        String content = extractContent(root);
        Message msg = Message.assistant(content);
        Choice choice = Choice.of(msg, "stop");
        
        Usage usage = null;
        if (response.getUsage() != null) {
            usage = new Usage(
                response.getUsage().getInputTokens() != null ? response.getUsage().getInputTokens() : 0,
                response.getUsage().getOutputTokens() != null ? response.getUsage().getOutputTokens() : 0,
                (response.getUsage().getInputTokens() != null ? response.getUsage().getInputTokens() : 0) + 
                (response.getUsage().getOutputTokens() != null ? response.getUsage().getOutputTokens() : 0),
                0.0
            );
        }

        return new CompletionResult(
                request.id() != null ? request.id() : UUID.randomUUID().toString(),
                response.getModel() != null ? response.getModel() : request.model(),
                List.of(choice),
                usage,
                Map.of("provider", PROVIDER_ID),
                0
        );
    }

    @Override
    public CompletionStream stream(CompletionRequest request) throws Exception {
        if (!initialized) initialize();

        AnthropicRequest anthropicRequest = buildAnthropicRequest(request);
        anthropicRequest.setStream(true);
        String currentApiKey = getApiKey(request);

        if (currentApiKey == null || currentApiKey.isBlank()) {
            throw new RuntimeException("Anthropic API key not configured. Set ANTHROPIC_API_KEY environment variable.");
        }

        String baseUrl = (configDetails != null && configDetails.baseUrl() != null) ? configDetails.baseUrl() : "https://api.anthropic.com";
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String url = baseUrl + "/v1/messages";

        String body = objectMapper.writeValueAsString(anthropicRequest);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-api-key", currentApiKey)
                .header("anthropic-version", API_VERSION)
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        BlockingQueue<CompletionResult> queue = new LinkedBlockingQueue<>();
        CompletionResult endMarker = new CompletionResult("END", null, null, null, null, 0);

        httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
            .thenAccept(resp -> {
                if (resp.statusCode() != 200) {
                    log.error("Anthropic streaming failed: " + resp.statusCode());
                    queue.offer(endMarker);
                    return;
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6).trim();
                            if (!data.isEmpty()) {
                                try {
                                    com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(data);
                                    String eventType = root.path("type").asText("");
                                    if ("content_block_delta".equals(eventType)) {
                                        String content = root.path("delta").path("text").asText("");
                                        if (content != null && !content.isBlank()) {
                                            Message msg = Message.assistant(content);
                                            Choice choice = Choice.of(msg, null);
                                            queue.offer(new CompletionResult(
                                                request.id() != null ? request.id() : UUID.randomUUID().toString(),
                                                request.model(),
                                                List.of(choice),
                                                null,
                                                Map.of(),
                                                0
                                            ));
                                        }
                                    } else if ("message_stop".equals(eventType)) {
                                        queue.offer(endMarker);
                                    }
                                } catch (Exception e) {
                                    log.warn("Failed to parse Anthropic chunk: " + data, e);
                                }
                            }
                        }
                    }
                    if (queue.peek() != endMarker && !queue.contains(endMarker)) {
                        queue.offer(endMarker);
                    }
                } catch (Exception e) {
                    log.error("Error reading stream", e);
                    queue.offer(endMarker);
                }
            })
            .exceptionally(t -> {
                log.error("Error in Anthropic streaming", t);
                queue.offer(endMarker);
                return null;
            });

        return new CompletionStream() {
            private CompletionResult nextItem = null;
            private boolean complete = false;

            private void fetchNext() {
                if (nextItem == null && !complete) {
                    try {
                        CompletionResult res = queue.take();
                        if (res == endMarker) {
                            complete = true;
                        } else {
                            nextItem = res;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        complete = true;
                    }
                }
            }

            @Override
            public boolean hasNext() {
                fetchNext();
                return nextItem != null;
            }

            @Override
            public CompletionResult next() {
                fetchNext();
                if (nextItem == null) throw new NoSuchElementException();
                CompletionResult res = nextItem;
                nextItem = null;
                return res;
            }

            @Override
            public void close() throws Exception {
                // HttpClient streaming body will close when process stops
                complete = true;
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

    private AnthropicRequest buildAnthropicRequest(CompletionRequest request) {
        AnthropicRequest anthropicRequest = new AnthropicRequest();
        String model = request.model() != null ? request.model().trim() : "claude-3-sonnet-20240229";
        anthropicRequest.setModel(model);

        if (request.messages() != null) {
            List<AnthropicMessage> messages = request.messages().stream()
                    .map(msg -> new AnthropicMessage(msg.role().toString().toLowerCase(), msg.content()))
                    .collect(Collectors.toList());
            anthropicRequest.setMessages(messages);
        }

        if (request.parameters() != null) {
            if (request.parameters().containsKey("max_tokens")) {
                anthropicRequest.setMaxTokens(((Number) request.parameters().get("max_tokens")).intValue());
            } else {
                anthropicRequest.setMaxTokens(4096);
            }
            if (request.parameters().containsKey("temperature")) {
                anthropicRequest.setTemperature(((Number) request.parameters().get("temperature")).doubleValue());
            }
            if (request.parameters().containsKey("top_p")) {
                anthropicRequest.setTopP(((Number) request.parameters().get("top_p")).doubleValue());
            }
            if (request.parameters().containsKey("top_k")) {
                anthropicRequest.setTopK(((Number) request.parameters().get("top_k")).intValue());
            }
        } else {
            anthropicRequest.setMaxTokens(4096);
        }

        return anthropicRequest;
    }

    private String getApiKey(CompletionRequest request) {
        if (request != null && request.metadata() != null) {
            Object dynamicKey = request.metadata().get("apiKey");
            if (dynamicKey != null) {
                return dynamicKey.toString();
            }
        }
        if (configDetails != null) {
            String key = configDetails.apiKey();
            if (key != null && !key.isBlank() && !"dummy".equals(key)) {
                return key;
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
        } catch (Exception e) {}

        String sysProp = System.getProperty(PROVIDER_ID + ".api.key");
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp;
        }

        return System.getenv("ANTHROPIC_API_KEY");
    }

    private String extractContent(com.fasterxml.jackson.databind.JsonNode root) {
        if (root == null) return "";
        com.fasterxml.jackson.databind.JsonNode content = root.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) return "";
        
        StringBuilder sb = new StringBuilder();
        for (com.fasterxml.jackson.databind.JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                sb.append(block.path("text").asText(""));
            }
        }
        return sb.toString();
    }
}
