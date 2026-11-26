package tech.kayys.andalus.execution.snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tech.kayys.andalus.agent.AgentRequest;
import tech.kayys.andalus.agent.AgentResponse;
import tech.kayys.andalus.input.InputType;
import tech.kayys.andalus.resource.Artifact;

/**
 * Explicit checkpoint codec for the public AgentRequest and AgentResponse contracts.
 *
 * <p>Input types are stored by their enum token and artifacts are delegated to the
 * allow-listed artifact codec. Arbitrary request/response map values are handled by
 * the safe value algebra; unsupported object graphs are rejected.</p>
 */
public final class AgentMessageCheckpointCodec {
    private final CheckpointValueCodec values;
    private final ArtifactCheckpointCodec artifacts;

    public AgentMessageCheckpointCodec(CheckpointValueCodec values, ArtifactCheckpointCodec artifacts) {
        this.values = Objects.requireNonNull(values, "values");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    public AgentRequestValue encode(AgentRequest request) {
        Objects.requireNonNull(request, "request");
        return new AgentRequestValue(request.id(), request.sessionId(), request.tenantId(),
                request.userId(), request.type() == null ? null : request.type().name(),
                request.content(), encodeMap(request.metadata()), encodeArtifacts(request.artifacts()),
                encodeMap(request.parameters()), request.timestamp() == null ? null : request.timestamp().toString());
    }

    public AgentRequest decode(AgentRequestValue value) {
        Objects.requireNonNull(value, "value");
        return new AgentRequest(value.id(), value.sessionId(), value.tenantId(), value.userId(),
                value.inputType() == null ? null : InputType.valueOf(value.inputType()),
                value.content(), decodeMap(value.metadata()), decodeArtifacts(value.artifacts()),
                decodeMap(value.parameters()),
                value.timestamp() == null ? null : java.time.Instant.parse(value.timestamp()));
    }

    public AgentResponseValue encode(AgentResponse response) {
        Objects.requireNonNull(response, "response");
        return new AgentResponseValue(response.id(), response.sessionId(), response.content(),
                response.type(), encodeArtifacts(response.artifacts()), encodeMap(response.metadata()),
                encodeMap(response.data()), response.success(), response.error(), response.responseTimeMs(),
                response.timestamp() == null ? null : response.timestamp().toString());
    }

    public AgentResponse decode(AgentResponseValue value) {
        Objects.requireNonNull(value, "value");
        return new AgentResponse(value.id(), value.sessionId(), value.content(), value.type(),
                decodeArtifacts(value.artifacts()), decodeMap(value.metadata()), decodeMap(value.data()),
                value.success(), value.error(), value.responseTimeMs(),
                value.timestamp() == null ? null : java.time.Instant.parse(value.timestamp()));
    }

    private Map<String, CheckpointValueCodec.CheckpointValue> encodeMap(Map<String, Object> source) {
        if (source == null) return null;
        Map<String, CheckpointValueCodec.CheckpointValue> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, values.encode(value)));
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Object> decodeMap(Map<String, CheckpointValueCodec.CheckpointValue> source) {
        if (source == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, values.decode(value)));
        return Collections.unmodifiableMap(result);
    }

    private List<ArtifactCheckpointCodec.ArtifactValue> encodeArtifacts(List<Artifact> source) {
        if (source == null) return null;
        List<ArtifactCheckpointCodec.ArtifactValue> result = new ArrayList<>(source.size());
        for (Artifact artifact : source) result.add(artifacts.encode(artifact));
        return List.copyOf(result);
    }

    private List<Artifact> decodeArtifacts(List<ArtifactCheckpointCodec.ArtifactValue> source) {
        if (source == null) return null;
        List<Artifact> result = new ArrayList<>(source.size());
        for (ArtifactCheckpointCodec.ArtifactValue artifact : source) result.add(artifacts.decode(artifact));
        return List.copyOf(result);
    }

    public record AgentRequestValue(String id, String sessionId, String tenantId, String userId,
            String inputType, String content,
            Map<String, CheckpointValueCodec.CheckpointValue> metadata,
            List<ArtifactCheckpointCodec.ArtifactValue> artifacts,
            Map<String, CheckpointValueCodec.CheckpointValue> parameters, String timestamp) {
        public AgentRequestValue {
            metadata = immutableNullableMap(metadata);
            artifacts = artifacts == null ? null : List.copyOf(artifacts);
            parameters = immutableNullableMap(parameters);
        }
    }

    public record AgentResponseValue(String id, String sessionId, String content, String type,
            List<ArtifactCheckpointCodec.ArtifactValue> artifacts,
            Map<String, CheckpointValueCodec.CheckpointValue> metadata,
            Map<String, CheckpointValueCodec.CheckpointValue> data,
            boolean success, String error, long responseTimeMs, String timestamp) {
        public AgentResponseValue {
            artifacts = artifacts == null ? null : List.copyOf(artifacts);
            metadata = immutableNullableMap(metadata);
            data = immutableNullableMap(data);
        }
    }

    private static <K, V> Map<K, V> immutableNullableMap(Map<K, V> source) {
        return source == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
