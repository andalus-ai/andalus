package tech.kayys.andalus.execution.snapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tech.kayys.andalus.spi.output.OutputResult;
import tech.kayys.andalus.spi.output.OutputType;
import tech.kayys.andalus.execution.snapshot.CheckpointValueCodec.CheckpointValue;

/**
 * Explicit, versioned checkpoint representation for Framework OutputResult.
 *
 * <p>Output metadata is encoded through the restricted checkpoint value
 * algebra, and artifacts are delegated to {@link ArtifactCheckpointCodec}.
 * No arbitrary object serialization or reflective class loading is used.</p>
 */
public final class OutputResultCheckpointCodec {
    public static final int SCHEMA_VERSION = 1;

    private final CheckpointValueCodec values;
    private final ArtifactCheckpointCodec artifacts;

    public OutputResultCheckpointCodec(CheckpointValueCodec values, ArtifactCheckpointCodec artifacts) {
        this.values = Objects.requireNonNull(values, "values");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    public OutputResultValue encode(OutputResult result) {
        if (result == null) return null;
        List<ArtifactCheckpointCodec.ArtifactValue> encodedArtifacts = result.artifacts() == null
                ? null : result.artifacts().stream().map(artifacts::encode).toList();
        return new OutputResultValue(SCHEMA_VERSION, result.id(),
                result.type() == null ? null : result.type().name(), result.content(),
                encodedArtifacts, encodeMap(result.metadata()), result.isComplete(),
                result.timestamp() == null ? null : result.timestamp().toString(),
                result.sessionId(), result.recipient());
    }

    public OutputResult decode(OutputResultValue value) {
        if (value == null) return null;
        requireSupportedVersion(value.schemaVersion());
        OutputType type = value.type() == null ? null : OutputType.valueOf(value.type());
        List<tech.kayys.andalus.resource.Artifact> decodedArtifacts = value.artifacts() == null
                ? null : value.artifacts().stream().map(artifacts::decode).toList();
        Instant timestamp = value.timestamp() == null ? null : Instant.parse(value.timestamp());
        return new OutputResult(value.id(), type, value.content(), decodedArtifacts,
                decodeMap(value.metadata()), value.complete(), timestamp,
                value.sessionId(), value.recipient());
    }

    private Map<String, CheckpointValue> encodeMap(Map<String, ?> source) {
        if (source == null) return null;
        Map<String, CheckpointValue> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, values.encode(value)));
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Object> decodeMap(Map<String, CheckpointValue> source) {
        if (source == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, values.decode(value)));
        return Collections.unmodifiableMap(result);
    }

    private void requireSupportedVersion(int version) {
        if (version != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported OutputResult checkpoint schema version: " + version);
        }
    }

    public record OutputResultValue(
            int schemaVersion,
            String id,
            String type,
            String content,
            List<ArtifactCheckpointCodec.ArtifactValue> artifacts,
            Map<String, CheckpointValue> metadata,
            boolean complete,
            String timestamp,
            String sessionId,
            String recipient) {
        public OutputResultValue {
            artifacts = artifacts == null ? null : List.copyOf(artifacts);
            metadata = metadata == null ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }
    }
}
