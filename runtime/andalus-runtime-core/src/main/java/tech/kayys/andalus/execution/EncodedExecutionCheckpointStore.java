package tech.kayys.andalus.execution;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tech.kayys.andalus.execution.snapshot.ExecutionCheckpointEnvelopeCodec;

/**
 * Adapts the domain lifecycle store to a persistence repository containing
 * only explicit, versioned checkpoint DTOs.
 *
 * <p>This keeps persistence implementations independent of live AgentContext
 * object graphs and prevents Java serialization from becoming a storage format.</p>
 */
public final class EncodedExecutionCheckpointStore implements ExecutionCheckpointStore {
    private final ExecutionCheckpointValueStore values;
    private final ExecutionCheckpointEnvelopeCodec codec;

    public EncodedExecutionCheckpointStore(
            ExecutionCheckpointValueStore values,
            ExecutionCheckpointEnvelopeCodec codec) {
        this.values = Objects.requireNonNull(values, "values");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public void save(ExecutionCheckpoint checkpoint) {
        values.save(codec.encode(Objects.requireNonNull(checkpoint, "checkpoint")));
    }

    @Override
    public Optional<ExecutionCheckpoint> load(String executionId) {
        return values.loadLatest(executionId).map(codec::decode);
    }

    @Override
    public Optional<ExecutionCheckpoint> load(
            String executionId, ExecutionCheckpointId checkpointId) {
        Objects.requireNonNull(checkpointId, "checkpointId");
        return values.load(executionId, checkpointId.value()).map(codec::decode);
    }

    @Override
    public List<ExecutionCheckpoint> history(String executionId) {
        return values.history(executionId).stream().map(codec::decode).toList();
    }

    @Override
    public void delete(String executionId) {
        values.delete(executionId);
    }
}
