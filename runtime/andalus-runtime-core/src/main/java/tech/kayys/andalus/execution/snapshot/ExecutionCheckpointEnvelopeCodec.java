package tech.kayys.andalus.execution.snapshot;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tech.kayys.andalus.execution.ExecutionCheckpoint;
import tech.kayys.andalus.execution.ExecutionCheckpointId;
import tech.kayys.andalus.execution.ExecutionPhase;
import tech.kayys.andalus.execution.ExecutionStatus;
import tech.kayys.andalus.execution.snapshot.CheckpointValueCodec.CheckpointValue;

/**
 * Versioned codec for the complete lifecycle checkpoint envelope.
 *
 * <p>The aggregate context is encoded by AgentContextCheckpointCodec. Caller
 * ownership and security metadata remain in this envelope and are never
 * reconstructed from AgentContext.</p>
 */
public final class ExecutionCheckpointEnvelopeCodec {
    public static final int SCHEMA_VERSION = 1;

    private final AgentContextCheckpointCodec contexts;
    private final CheckpointValueCodec values;

    public ExecutionCheckpointEnvelopeCodec(
            AgentContextCheckpointCodec contexts,
            CheckpointValueCodec values) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.values = Objects.requireNonNull(values, "values");
    }

    public ExecutionCheckpointValue encode(ExecutionCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Map<String, CheckpointValue> metadata = new LinkedHashMap<>();
        checkpoint.metadata().forEach((key, value) -> metadata.put(key, values.encode(value)));
        return new ExecutionCheckpointValue(
                SCHEMA_VERSION,
                checkpoint.checkpointId().value(),
                checkpoint.executionId(),
                checkpoint.status().name(),
                checkpoint.phase().name(),
                checkpoint.attempt(),
                checkpoint.iteration(),
                contexts.encode(checkpoint.context()),
                checkpoint.createdAt().toString(),
                metadata);
    }

    public ExecutionCheckpoint decode(ExecutionCheckpointValue value) {
        Objects.requireNonNull(value, "value");
        if (value.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported execution checkpoint envelope schema version: " + value.schemaVersion());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        value.metadata().forEach((key, encoded) -> metadata.put(key, values.decode(encoded)));
        return new ExecutionCheckpoint(
                new ExecutionCheckpointId(value.checkpointId()),
                value.executionId(),
                ExecutionStatus.valueOf(value.status()),
                ExecutionPhase.valueOf(value.phase()),
                value.attempt(),
                value.iteration(),
                Objects.requireNonNull(contexts.decode(value.context()), "checkpoint context"),
                Instant.parse(value.createdAt()),
                Collections.unmodifiableMap(metadata));
    }

    public record ExecutionCheckpointValue(
            int schemaVersion,
            String checkpointId,
            String executionId,
            String status,
            String phase,
            int attempt,
            int iteration,
            AgentContextCheckpointCodec.AgentContextValue context,
            String createdAt,
            Map<String, CheckpointValue> metadata) {
        public ExecutionCheckpointValue {
            Objects.requireNonNull(checkpointId, "checkpointId");
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(createdAt, "createdAt");
            metadata = metadata == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }
    }
}
