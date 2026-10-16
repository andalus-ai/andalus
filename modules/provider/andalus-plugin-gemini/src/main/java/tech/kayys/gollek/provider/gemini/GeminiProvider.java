package tech.kayys.aqli.provider.gemini;

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
import java.util.stream.StreamSupport;

/**
 * Google Gemini provider adapter for cloud LLM inference.
 * Standardized on OpenAI-compatible API for maximum stability.
 */
@ApplicationScoped
public class GeminiProvider implements InferenceProvider {

    private static final String PROVIDER_ID = "gemini";
    private static final String PROVIDER_NAME = "Google Gemini";
    private static final String VERSION = "1.0.0";
    private static final String GEMINI_OPENAI_COMPAT_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
    private static final long MAX_AUTO_RETRY_MS = 60_000L;
    private static final Pattern RETRY_SECONDS_PATTERN = Pattern.compile("retry\\s+in\\s+([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE);
    private static final Logger log = Logger.getLogger(GeminiProvider.class);

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
                .description("Google Gemini - multimodal AI with large context window")
                .version(VERSION)
                .label("vendor", "Google")
                .label("homepage", "https://ai.google.dev/docs")
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
        log.info("Gemini provider initialized");
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
                Matcher m = Pattern.compile("api\\.key:\\s*(.+)").matcher(content);
                if (m.find()) {
                    return m.group(1).trim();
                }
            }
        } catch (Exception e) { }

        String envKey = System.getenv("GEMINI_API_KEY");
        if (envKey == null || envKey.isBlank()) {
            envKey = System.getenv("GOOGLE_API_KEY");
        }
        return envKey;
    }

    @Override
    public Set<String> listModels() {
        return Set.of("gemini-2.5-flash", "gemini-2.5-pro", "gemini-1.5-flash", "gemini-1.5-pro");
    }

    @Override
    public ModelInfo getModelInfo(String modelId) {
        return new ModelInfo(modelId, modelId, "gemini", Set.of("chat", "multimodal"), Map.of(
                "maxContextTokens", 2000000,
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

        GeminiRequest geminiRequest = buildOpenAICompatibleRequest(request);
        String currentApiKey = getApiKey(request);

        if (currentApiKey == null || currentApiKey.isBlank()) {
            throw new RuntimeException("Gemini API key not configured. Set GEMINI_API_KEY or GOOGLE_API_KEY environment variable.");
        }

        String url = GEMINI_OPENAI_COMPAT_URL;
        String body = objectMapper.writeValueAsString(geminiRequest);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + currentApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = sendWithQuotaRetry(httpRequest).toCompletableFuture().join();
        if (resp.statusCode() != 200) {
            throw buildHttpException(resp.statusCode(), resp.body());
        }

        com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(resp.body());
        String content = extractContent(root, false);
        GeminiResponse response = objectMapper.treeToValue(root, GeminiResponse.class);
        
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
        GeminiRequest geminiRequest = buildOpenAICompatibleRequest(request);
        geminiRequest.setStream(true);
        String currentApiKey = getApiKey(request);

        if (currentApiKey == null || currentApiKey.isBlank()) {
            throw new RuntimeException("Gemini API key not configured. Set GEMINI_API_KEY or GOOGLE_API_KEY environment variable.");
        }

        String url = GEMINI_OPENAI_COMPAT_URL;
        String body = objectMapper.writeValueAsString(geminiRequest);
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
                            if (resp.statusCode() == 429) {
                                emitter.fail(new RuntimeException("Gemini quota exceeded (429). Wait before retrying."));
                            } else {
                                emitter.fail(new RuntimeException("Gemini streaming failed: " + resp.statusCode()));
                            }
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
                                            log.warn("Failed to parse Gemini chunk: " + data, e);
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

    private GeminiRequest buildOpenAICompatibleRequest(CompletionRequest request) {
        GeminiRequest geminiRequest = new GeminiRequest();
        String model = request.model() != null ? request.model().trim() : "gemini-2.5-flash";
        geminiRequest.setModel(model);

        if (request.messages() != null) {
            List<GeminiMessage> messages = request.messages().stream()
                    .map(msg -> new GeminiMessage(mapRole(msg.role().toString()), msg.content()))
                    .collect(Collectors.toList());
            geminiRequest.setMessages(messages);
        }

        Map<String, Object> params = request.parameters();
        if (params != null) {
            if (params.containsKey("temperature")) {
                geminiRequest.setTemperature(((Number) params.get("temperature")).doubleValue());
            }
            if (params.containsKey("max_tokens")) {
                geminiRequest.setMaxTokens(((Number) params.get("max_tokens")).intValue());
            }
            if (params.containsKey("top_p")) {
                geminiRequest.setTopP(((Number) params.get("top_p")).doubleValue());
            }
        }
        return geminiRequest;
    }

    private String mapRole(String role) {
        String r = role.toLowerCase();
        return switch (r) {
            case "user" -> "user";
            case "assistant" -> "assistant";
            case "system" -> "system";
            default -> "user";
        };
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
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) return "";
        if (contentNode.isTextual()) return contentNode.asText("");
        if (contentNode.isObject()) {
            String text = contentNode.path("text").asText("");
            if (!text.isBlank()) return text;
            com.fasterxml.jackson.databind.JsonNode parts = contentNode.path("parts");
            if (parts.isArray()) return readContentNode(parts);
            return "";
        }
        if (contentNode.isArray()) {
            StringJoiner joiner = new StringJoiner("");
            for (com.fasterxml.jackson.databind.JsonNode part : contentNode) {
                if (part == null || part.isNull()) continue;
                if (part.isTextual()) {
                    joiner.add(part.asText(""));
                    continue;
                }
                String type = part.path("type").asText("");
                String text = part.path("text").asText("");
                if (!text.isBlank()) {
                    joiner.add(text);
                    continue;
                }
                if ("text".equalsIgnoreCase(type) || "output_text".equalsIgnoreCase(type) || "input_text".equalsIgnoreCase(type) || type.isBlank()) {
                    String nested = readContentNode(part.path("content"));
                    if (!nested.isBlank()) {
                        joiner.add(nested);
                    }
                }
            }
            return joiner.toString();
        }
        return "";
    }

    private CompletionStage<HttpResponse<String>> sendWithQuotaRetry(HttpRequest httpRequest) {
        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenCompose(resp -> {
                    if (resp.statusCode() != 429) {
                        return CompletableFuture.completedFuture(resp);
                    }
                    long retryDelayMs = extractRetryDelayMillis(resp.body());
                    if (retryDelayMs <= 0 || retryDelayMs > MAX_AUTO_RETRY_MS) {
                        return CompletableFuture.completedFuture(resp);
                    }
                    log.warnf("Gemini quota hit; retrying once after %d ms", retryDelayMs);
                    return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(retryDelayMs, TimeUnit.MILLISECONDS))
                            .thenCompose(ignored -> httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString()));
                });
    }

    private RuntimeException buildHttpException(int statusCode, String body) {
        if (statusCode == 429) {
            String message = extractErrorMessage(body);
            return new RuntimeException("Gemini quota exceeded (429). " + message);
        }
        String message = extractErrorMessage(body);
        return new RuntimeException("Gemini failed: " + statusCode + " - " + message);
    }

    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) return "No error details";
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(body);
            String message = root.path("error").path("message").asText("");
            if (!message.isBlank()) return message.replace('\n', ' ').trim();
        } catch (Exception ignored) { }
        return body.length() > 240 ? body.substring(0, 240) + "..." : body;
    }

    private long extractRetryDelayMillis(String body) {
        return -1L;
    }
}
