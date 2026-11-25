package tech.kayys.andalus.execution.snapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tech.kayys.andalus.agent.AgentContext;
import tech.kayys.andalus.agent.AgentContextPhase;
import tech.kayys.andalus.agent.AgentContextState;
import tech.kayys.andalus.execution.snapshot.CheckpointValueCodec.CheckpointValue;
import tech.kayys.andalus.extension.Id;
import tech.kayys.andalus.identity.ResourceId;
import tech.kayys.andalus.resource.Artifact;
import tech.kayys.andalus.resource.ResourceType;

/**
 * Explicit versioned checkpoint mapping for the Framework AgentContext aggregate.
 *
 * <p>This codec deliberately uses allow-listed component codecs and the restricted
 * value algebra. It never uses Java serialization, reflective class loading, or
 * stringification as a fallback for unsupported values.</p>
 *
 * <p>History is recursively represented only up to the configured depth. The DTO
 * records when the tail was truncated so callers can distinguish a complete
 * history from a bounded snapshot.</p>
 */
public final class AgentContextCheckpointCodec {
    public static final int SCHEMA_VERSION = 1;
    public static final int DEFAULT_MAX_HISTORY_DEPTH = 8;

    private final CheckpointValueCodec values;
    private final ArtifactCheckpointCodec artifacts;
    private final AgentMessageCheckpointCodec messages;
    private final ContextDataCheckpointCodec contextData;
    private final OutputResultCheckpointCodec outputResults;
    private final int maxHistoryDepth;

    public AgentContextCheckpointCodec(
            CheckpointValueCodec values,
            ArtifactCheckpointCodec artifacts,
            AgentMessageCheckpointCodec messages,
            ContextDataCheckpointCodec contextData,
            OutputResultCheckpointCodec outputResults) {
        this(values, artifacts, messages, contextData, outputResults, DEFAULT_MAX_HISTORY_DEPTH);
    }

    public AgentContextCheckpointCodec(
            CheckpointValueCodec values,
            ArtifactCheckpointCodec artifacts,
            AgentMessageCheckpointCodec messages,
            ContextDataCheckpointCodec contextData,
            OutputResultCheckpointCodec outputResults,
            int maxHistoryDepth) {
        this.values = Objects.requireNonNull(values, "values");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.contextData = Objects.requireNonNull(contextData, "contextData");
        this.outputResults = Objects.requireNonNull(outputResults, "outputResults");
        if (maxHistoryDepth < 0) {
            throw new IllegalArgumentException("maxHistoryDepth must be >= 0");
        }
        this.maxHistoryDepth = maxHistoryDepth;
    }

    public AgentContextValue encode(AgentContext context) {
        return context == null ? null : encode(context, 0);
    }

    public AgentContext decode(AgentContextValue value) {
        return value == null ? null : decode(value, 0);
    }

    private AgentContextValue encode(AgentContext context, int depth) {
        ResourceId id = Objects.requireNonNull(context.id(), "AgentContext.id");
        List<ArtifactCheckpointCodec.ArtifactValue> encodedArtifacts = new ArrayList<>();
        for (Artifact artifact : context.artifacts()) {
            encodedArtifacts.add(artifacts.encode(artifact));
        }

        List<AgentContextValue> encodedHistory = new ArrayList<>();
        boolean historyTruncated = false;
        int omittedHistoryEntries = 0;
        if (!context.history().isEmpty()) {
            if (depth >= maxHistoryDepth) {
                historyTruncated = true;
                omittedHistoryEntries = context.history().size();
            } else {
                for (AgentContext historical : context.history()) {
                    encodedHistory.add(encode(Objects.requireNonNull(historical, "history entry"), depth + 1));
                }
            }
        }

        return new AgentContextValue(
                SCHEMA_VERSION,
                id.type().name(),
                id.asString(),
                context.sessionId(),
                context.tenantId(),
                context.namespace(),
                context.request() == null ? null : messages.encode(context.request()),
                context.response() == null ? null : messages.encode(context.response()),
                encodeMap(context.variables()),
                encodeMap(context.attributes()),
                encodedArtifacts,
                contextData.encode(context.contextData()),
                outputResults.encode(context.outputResult()),
                context.state().name(),
                context.createdAt().toString(),
                context.updatedAt().toString(),
                context.version(),
                new LinkedHashMap<>(context.contextMetadata()),
                context.currentPhase().name(),
                context.startTime(),
                context.elapsedTime(),
                encodeLongMap(context.phaseTimings()),
                new ArrayList<>(context.completedPhases()),
                encodedHistory,
                historyTruncated,
                omittedHistoryEntries);
    }

    private AgentContext decode(AgentContextValue value, int depth) {
        requireSupportedVersion(value.schemaVersion());
        ResourceId id = ResourceId.from(Id.fromString(value.idValue()), ResourceType.fromString(value.idType()));

        List<Artifact> decodedArtifacts = new ArrayList<>();
        for (ArtifactCheckpointCodec.ArtifactValue artifact : value.artifacts()) {
            decodedArtifacts.add(artifacts.decode(artifact));
        }

        List<AgentContext> decodedHistory = new ArrayList<>();
        if (!value.historyTruncated()) {
            for (AgentContextValue historical : value.history()) {
                decodedHistory.add(decode(historical, depth + 1));
            }
        }

        return AgentContext.builder()
                .id(id)
                .sessionId(value.sessionId())
                .tenantId(value.tenantId())
                .namespace(value.namespace())
                .request(value.request() == null ? null : messages.decode(value.request()))
                .response(value.response() == null ? null : messages.decode(value.response()))
                .variables(decodeMap(value.variables()))
                .attributes(decodeMap(value.attributes()))
                .artifacts(decodedArtifacts)
                .contextData(contextData.decode(value.contextData()))
                .outputResult(outputResults.decode(value.outputResult()))
                .state(AgentContextState.valueOf(value.state()))
                .createdAt(Instant.parse(value.createdAt()))
                .updatedAt(Instant.parse(value.updatedAt()))
                .version(value.version())
                .metadata(value.metadata())
                .currentPhase(AgentContextPhase.valueOf(value.currentPhase()))
                .startTime(value.startTime())
                .elapsedTime(value.elapsedTime())
                .phaseTimings(decodeLongMap(value.phaseTimings()))
                .completedPhases(new LinkedHashSet<>(value.completedPhases()))
                .history(decodedHistory)
                .build();
    }

    private Map<String, CheckpointValue> encodeMap(Map<String, ?> source) {
        Map<String, CheckpointValue> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, values.encode(value)));
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Object> decodeMap(Map<String, CheckpointValue> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, values.decode(value)));
        return Collections.unmodifiableMap(result);
    }

    private Map<String, CheckpointValue> encodeLongMap(Map<String, Long> source) {
        Map<String, CheckpointValue> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, values.encode(value)));
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Long> decodeLongMap(Map<String, CheckpointValue> source) {
        Map<String, Long> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            Object decoded = values.decode(value);
            if (decoded == null) {
                result.put(key, null);
            } else if (decoded instanceof Number number) {
                result.put(key, new java.math.BigDecimal(number.toString()).longValueExact());
            } else {
                throw new IllegalArgumentException("Phase timing must be numeric for key: " + key);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private void requireSupportedVersion(int version) {
        if (version != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported AgentContext checkpoint schema version: " + version);
        }
    }

    public record AgentContextValue(
            int schemaVersion,
            String idType,
            String idValue,
            String sessionId,
            String tenantId,
            String namespace,
            AgentMessageCheckpointCodec.AgentRequestValue request,
            AgentMessageCheckpointCodec.AgentResponseValue response,
            Map<String, CheckpointValue> variables,
            Map<String, CheckpointValue> attributes,
            List<ArtifactCheckpointCodec.ArtifactValue> artifacts,
            ContextDataCheckpointCodec.ContextDataValue contextData,
            OutputResultCheckpointCodec.OutputResultValue outputResult,
            String state,
            String createdAt,
            String updatedAt,
            int version,
            Map<String, String> metadata,
            String currentPhase,
            long startTime,
            long elapsedTime,
            Map<String, CheckpointValue> phaseTimings,
            List<String> completedPhases,
            List<AgentContextValue> history,
            boolean historyTruncated,
            int omittedHistoryEntries) {
        public AgentContextValue {
            Objects.requireNonNull(idType, "idType");
            Objects.requireNonNull(idValue, "idValue");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            Objects.requireNonNull(currentPhase, "currentPhase");
            if (omittedHistoryEntries < 0) {
                throw new IllegalArgumentException("omittedHistoryEntries must be >= 0");
            }
            variables = immutableMap(variables);
            attributes = immutableMap(attributes);
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
            phaseTimings = immutableMap(phaseTimings);
            completedPhases = completedPhases == null ? List.of() : List.copyOf(completedPhases);
            history = history == null ? List.of() : List.copyOf(history);
            if (!historyTruncated && omittedHistoryEntries != 0) {
                throw new IllegalArgumentException("omittedHistoryEntries requires historyTruncated=true");
            }
        }

        private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
            return source == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
    }
}
