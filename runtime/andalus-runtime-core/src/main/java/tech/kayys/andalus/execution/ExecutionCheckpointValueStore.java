package tech.kayys.andalus.execution;

import java.util.List;
import java.util.Optional;
import tech.kayys.andalus.execution.snapshot.ExecutionCheckpointEnvelopeCodec.ExecutionCheckpointValue;

/**
 * Persistence-facing repository for versioned checkpoint values.
 *
 * <p>Implementations own transactions, uniqueness, retention and durable storage.
 * They must preserve insertion order within an execution's history.</p>
 */
public interface ExecutionCheckpointValueStore {
    void save(ExecutionCheckpointValue checkpoint);

    Optional<ExecutionCheckpointValue> loadLatest(String executionId);

    Optional<ExecutionCheckpointValue> load(
            String executionId, String checkpointId);

    List<ExecutionCheckpointValue> history(String executionId);

    void delete(String executionId);
}
