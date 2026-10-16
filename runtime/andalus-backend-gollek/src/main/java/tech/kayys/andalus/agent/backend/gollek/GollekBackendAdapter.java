package tech.kayys.andalus.agent.backend.aqli;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;
import tech.kayys.aqli.sdk.core.AqliSdk;
import tech.kayys.aqli.spi.Message;
import tech.kayys.aqli.spi.inference.StreamingInferenceChunk;
import tech.kayys.andalus.agent.spi.BackendCapabilities;
import tech.kayys.andalus.agent.spi.InferenceBackend;
import tech.kayys.andalus.agent.spi.InferenceRequest;
import tech.kayys.andalus.agent.spi.InferenceResponse;
import tech.kayys.andalus.agent.spi.InferenceTypes.AssistantMessage;
import tech.kayys.andalus.agent.spi.InferenceTypes.ChatMessage;
import tech.kayys.andalus.agent.spi.InferenceTypes.ContentPart;
import tech.kayys.andalus.agent.spi.InferenceTypes.ImagePart;
import tech.kayys.andalus.agent.spi.InferenceTypes.ProviderInfo;
import tech.kayys.andalus.agent.spi.InferenceTypes.StreamingChunk;
import tech.kayys.andalus.agent.spi.InferenceTypes.SystemMessage;
import tech.kayys.andalus.agent.spi.InferenceTypes.TextPart;
import tech.kayys.andalus.agent.spi.InferenceTypes.TokenUsage;
import tech.kayys.andalus.agent.spi.InferenceTypes.ToolCall;
import tech.kayys.andalus.agent.spi.InferenceTypes.ToolDefinition;
import tech.kayys.andalus.agent.spi.InferenceTypes.ToolResultMessage;
import tech.kayys.andalus.agent.spi.InferenceTypes.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Backend adapter that wraps Aqli SDK to implement the backend-agnostic
 * {@link InferenceBackend} SPI.
 *
 * <p>
 * This adapter translates between andalus-aqli's backend-agnostic SPI types
 * and Aqli SDK's native types, enabling any code that depends on
 * {@code InferenceBackend} to work with Aqli without direct coupling.
 * </p>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * // Programmatic (no Quarkus)
 * AqliSdk sdk = AqliSdk.builder().build();
 * InferenceBackend backend = new AqliBackendAdapter(sdk);
 * backend.initialize(config);
 *
 * Uni<InferenceResponse> response = backend.infer(request);
 * }</pre>
 *
 * <h3>Features:</h3>
 * <ul>
 *   <li>Full inference support (sync + streaming)</li>
 *   <li>Provider discovery and health checking</li>
 *   <li>Capability detection from Aqli provider registry</li>
 *   <li>Graceful lifecycle management</li>
 * </ul>
 *
 * @author Andalus Team
 * @version 0.1.0
 * @since 2026-04-06
 */
public class AqliBackendAdapter implements InferenceBackend {

    private static final Logger LOG = Logger.getLogger(AqliBackendAdapter.class);

    private final AqliSdk aqliSdk;
    private volatile BackendCapabilities capabilities = BackendCapabilities.none();
    private volatile boolean initialized;

    public AqliBackendAdapter(AqliSdk aqliSdk) {
        this.aqliSdk = Objects.requireNonNull(aqliSdk, "aqliSdk");
    }

    @Override
    public String name() {
        return "aqli";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public Uni<InferenceResponse> infer(InferenceRequest request) {
        ensureInitialized();

        tech.kayys.aqli.spi.inference.InferenceRequest aqliRequest = toAqliRequest(request);
        long startedAt = System.currentTimeMillis();

        return Uni.createFrom()
                .completionStage(aqliSdk.createCompletionAsync(aqliRequest))
                .map(response -> fromAqliResponse(request, response, startedAt))
                .onFailure().transform(error -> {
                    LOG.errorf(error, "Aqli inference failed for request %s", request.requestId());
                    return new RuntimeException("Aqli inference failed: " + error.getMessage(), error);
                });
    }

    @Override
    public Multi<StreamingChunk> stream(InferenceRequest request) {
        ensureInitialized();

        tech.kayys.aqli.spi.inference.InferenceRequest aqliRequest = toAqliRequest(
                new InferenceRequest(
                        request.requestId(),
                        request.model(),
                        request.messages(),
                        request.tools(),
                        request.toolChoice(),
                        request.temperature(),
                        request.maxTokens(),
                        request.topP(),
                        request.stopSequences(),
                        true,
                        request.timeout(),
                        request.metadata()));

        return aqliSdk.streamCompletion(aqliRequest)
                .map(this::fromAqliChunk)
                .onFailure().transform(error -> {
                    LOG.errorf(error, "Aqli streaming failed for request %s", request.requestId());
                    return new RuntimeException("Aqli streaming failed: " + error.getMessage(), error);
                });
    }

    @Override
    public List<ProviderInfo> listProviders() {
        ensureInitialized();
        try {
            return aqliSdk.listAvailableProviders().stream()
                    .map(this::fromAqliProvider)
                    .toList();
        } catch (Exception error) {
            LOG.warnf("Failed to list Aqli providers: %s", error.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean isHealthy() {
        if (!initialized) {
            return false;
        }
        try {
            return aqliSdk.listAvailableProviders().stream()
                    .anyMatch(provider -> provider.healthStatus()
                            == tech.kayys.aqli.spi.provider.ProviderHealth.Status.HEALTHY);
        } catch (Exception error) {
            LOG.debugf("Aqli health check failed: %s", error.getMessage());
            return false;
        }
    }

    @Override
    public BackendCapabilities capabilitiesInfo() {
        ensureInitialized();
        return capabilities;
    }

    @Override
    public long capabilities() {
        BackendCapabilities caps = capabilitiesInfo();
        long flags = 0;
        if (caps.streaming()) flags |= CAP_STREAMING;
        if (caps.toolCalling()) flags |= CAP_TOOL_CALLING;
        if (caps.multimodal()) flags |= CAP_MULTIMODAL;
        if (caps.structuredOutput()) flags |= CAP_STRUCTURED_OUTPUT;
        if (caps.parallelTools()) flags |= CAP_PARALLEL_TOOLS;
        if (caps.vision()) flags |= CAP_VISION;
        if (caps.audio()) flags |= CAP_AUDIO;
        if (caps.embedding()) flags |= CAP_EMBEDDING;
        return flags;
    }

    @Override
    public void initialize(Map<String, Object> config) {
        if (initialized) {
            return;
        }
        capabilities = detectCapabilities();
        initialized = true;
    }

    @Override
    public void shutdown() {
        initialized = false;
    }

    private tech.kayys.aqli.spi.inference.InferenceRequest toAqliRequest(InferenceRequest request) {
        tech.kayys.aqli.spi.inference.InferenceRequest.Builder builder =
                tech.kayys.aqli.spi.inference.InferenceRequest.builder()
                        .requestId(request.requestId())
                        .model(request.model())
                        .messages(request.messages().stream().map(this::toAqliMessage).toList())
                        .tools(request.tools().stream().map(this::toAqliTool).toList())
                        .toolChoice(request.toolChoice())
                        .temperature(request.temperature())
                        .maxTokens(request.maxTokens())
                        .topP(request.topP())
                        .streaming(request.stream())
                        .timeout(request.timeout())
                        .metadata(request.metadata());

        metadataValue(request, "apiKey", String.class).ifPresent(builder::apiKey);
        metadataValue(request, "preferredProvider", String.class).ifPresent(builder::preferredProvider);
        metadataValue(request, "plugin", String.class).ifPresent(builder::plugin);
        metadataValue(request, "userId", String.class).ifPresent(builder::userId);
        metadataValue(request, "sessionId", String.class).ifPresent(builder::sessionId);
        metadataValue(request, "traceId", String.class).ifPresent(builder::traceId);

        return builder.build();
    }

    private Message toAqliMessage(ChatMessage message) {
        return switch (message) {
            case SystemMessage system -> Message.system(system.content());
            case UserMessage user -> Message.user(userContent(user));
            case AssistantMessage assistant -> Message.assistant(assistant.content());
            case ToolResultMessage tool -> Message.tool(tool.toolCallId(), tool.content());
        };
    }

    private String userContent(UserMessage message) {
        if (message.parts().isEmpty()) {
            return message.content();
        }
        return message.parts().stream()
                .map(this::contentPartText)
                .filter(part -> !part.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse(message.content());
    }

    private String contentPartText(ContentPart part) {
        return switch (part) {
            case TextPart text -> text.text();
            case ImagePart image -> "[image:" + image.mimeType() + ":" + image.imageUrl() + "]";
        };
    }

    private tech.kayys.aqli.spi.tool.ToolDefinition toAqliTool(ToolDefinition tool) {
        return tech.kayys.aqli.spi.tool.ToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(tool.parameters())
                .build();
    }

    private InferenceResponse fromAqliResponse(
            InferenceRequest request,
            tech.kayys.aqli.spi.inference.InferenceResponse response,
            long startedAt) {

        AssistantMessage message = new AssistantMessage(
                response.getContent(),
                toAndalusToolCalls(response.getToolCalls()));

        return InferenceResponse.builder()
                .responseId(responseId(response, request))
                .requestId(response.getRequestId())
                .model(response.getModel())
                .message(message)
                .finishReason(finishReason(response.getFinishReason()))
                .usage(TokenUsage.of(response.getInputTokens(), response.getOutputTokens()))
                .durationMs(durationMs(response, startedAt))
                .build();
    }

    private List<ToolCall> toAndalusToolCalls(
            List<tech.kayys.aqli.spi.inference.InferenceResponse.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        AtomicInteger index = new AtomicInteger();
        return toolCalls.stream()
                .map(call -> new ToolCall(
                        "aqli-tool-call-" + index.incrementAndGet(),
                        "function",
                        call.name(),
                        String.valueOf(call.arguments())))
                .toList();
    }

    private StreamingChunk fromAqliChunk(StreamingInferenceChunk chunk) {
        TokenUsage usage = chunk.usage() == null
                ? null
                : TokenUsage.of(safeInt(chunk.usage().inputTokens()), safeInt(chunk.usage().outputTokens()));

        return new StreamingChunk(
                chunk.requestId(),
                chunk.delta(),
                List.of(),
                chunk.finished() ? finishReason(chunk.finishReason()) : null,
                usage);
    }

    private ProviderInfo fromAqliProvider(tech.kayys.aqli.spi.provider.ProviderInfo provider) {
        String model = provider.supportedModels().stream().findFirst().orElse("");
        boolean healthy = provider.healthStatus()
                == tech.kayys.aqli.spi.provider.ProviderHealth.Status.HEALTHY;

        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "vendor", provider.vendor());
        putIfPresent(metadata, "version", provider.version());
        putIfPresent(metadata, "description", provider.description());
        putIfPresent(metadata, "healthStatus", provider.healthStatus() == null ? null : provider.healthStatus().name());
        putIfPresent(metadata, "supportedModels", provider.supportedModels());
        putIfPresent(metadata, "metadata", provider.metadata());

        return new ProviderInfo(
                provider.id(),
                provider.name(),
                model,
                healthy,
                metadata);
    }

    private BackendCapabilities detectCapabilities() {
        try {
            List<tech.kayys.aqli.spi.provider.ProviderInfo> providers = aqliSdk.listAvailableProviders();
            boolean streaming = false;
            boolean toolCalling = false;
            boolean multimodal = false;
            boolean structuredOutput = false;
            boolean vision = false;
            boolean embedding = false;
            Set<String> supportedModels = new LinkedHashSet<>();

            for (tech.kayys.aqli.spi.provider.ProviderInfo provider : providers) {
                supportedModels.addAll(provider.supportedModels());
                tech.kayys.aqli.spi.provider.ProviderCapabilities caps = provider.capabilities();
                if (caps == null) {
                    continue;
                }
                streaming |= caps.isStreaming();
                toolCalling |= caps.isToolCalling() || caps.isFunctionCalling();
                multimodal |= caps.isMultimodal();
                structuredOutput |= caps.isStructuredOutputs();
                vision |= caps.isMultimodal();
                embedding |= caps.isEmbeddings();
                supportedModels.addAll(caps.getSupportedModels());
            }

            return new BackendCapabilities(
                    streaming,
                    toolCalling,
                    multimodal,
                    structuredOutput,
                    toolCalling,
                    vision,
                    false,
                    embedding,
                    Math.max(1, providers.size()),
                    new ArrayList<>(supportedModels));
        } catch (Exception error) {
            LOG.warnf("Failed to detect Aqli capabilities, using defaults: %s", error.getMessage());
            return BackendCapabilities.none();
        }
    }

    private String responseId(
            tech.kayys.aqli.spi.inference.InferenceResponse response,
            InferenceRequest request) {
        Object responseId = response.getMetadata() == null ? null : response.getMetadata().get("responseId");
        if (responseId != null) {
            return responseId.toString();
        }
        return response.getRequestId() != null ? response.getRequestId() : request.requestId();
    }

    private String finishReason(Object finishReason) {
        return finishReason == null ? "stop" : finishReason.toString().toLowerCase();
    }

    private long durationMs(tech.kayys.aqli.spi.inference.InferenceResponse response, long startedAt) {
        return response.getDurationMs() > 0
                ? response.getDurationMs()
                : System.currentTimeMillis() - startedAt;
    }

    private int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private <T> Optional<T> metadataValue(InferenceRequest request, String key, Class<T> type) {
        Object value = request.metadata().get(key);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private void ensureInitialized() {
        if (!initialized) {
            initialize(Map.of());
        }
    }
}
