package tech.kayys.andalus.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.agent.AgentContext;
import tech.kayys.andalus.execution.snapshot.AgentContextCheckpointCodec;
import tech.kayys.andalus.execution.snapshot.AgentMessageCheckpointCodec;
import tech.kayys.andalus.execution.snapshot.ArtifactCheckpointCodec;
import tech.kayys.andalus.execution.snapshot.CheckpointValueCodec;
import tech.kayys.andalus.execution.snapshot.ContextDataCheckpointCodec;
import tech.kayys.andalus.execution.snapshot.ExecutionCheckpointEnvelopeCodec;
import tech.kayys.andalus.execution.snapshot.OutputResultCheckpointCodec;

class EncodedExecutionCheckpointStoreTest {

    @Test
    void persistsOnlyEncodedValuesAndRehydratesDomainCheckpoints() {
        CheckpointValueCodec values = new CheckpointValueCodec();
        ArtifactCheckpointCodec artifacts = new ArtifactCheckpointCodec(values);
        AgentContextCheckpointCodec contexts = new AgentContextCheckpointCodec(
                values, artifacts,
                new AgentMessageCheckpointCodec(values, artifacts),
                new ContextDataCheckpointCodec(values),
                new OutputResultCheckpointCodec(values, artifacts));
        ExecutionCheckpointEnvelopeCodec codec = new ExecutionCheckpointEnvelopeCodec(contexts, values);
        InMemoryValueStore repository = new InMemoryValueStore();
        EncodedExecutionCheckpointStore store = new EncodedExecutionCheckpointStore(repository, codec);

        ExecutionCheckpoint first = ExecutionCheckpoint.of(
                "execution-1", ExecutionStatus.RUNNING, ExecutionPhase.INFERENCE, 1, 2,
                AgentContext.builder().sessionId("session-1").build(),
                Map.of("tenantId", "tenant-1", "principalType", "USER"));
        ExecutionCheckpoint second = ExecutionCheckpoint.of(
                "execution-1", ExecutionStatus.PAUSED, ExecutionPhase.TOOL, 1, 3,
                AgentContext.builder().sessionId("session-1").build(),
                Map.of("tenantId", "tenant-1", "principalType", "USER"));

        store.save(first);
        store.save(second);

        assertThat(repository.raw).allMatch(value -> value instanceof
                ExecutionCheckpointEnvelopeCodec.ExecutionCheckpointValue);
        assertThat(store.load("execution-1")).contains(second);
        assertThat(store.load("execution-1", first.checkpointId())).contains(first);
        assertThat(store.history("execution-1")).containsExactly(first, second);

        store.delete("execution-1");
        assertThat(store.load("execution-1")).isEmpty();
        assertThat(store.history("execution-1")).isEmpty();
    }

    private static final class InMemoryValueStore implements ExecutionCheckpointValueStore {
        private final Map<String, List<ExecutionCheckpointEnvelopeCodec.ExecutionCheckpointValue>> data =
                new HashMap<>();
        private final List<Object> raw = new ArrayList<>();

        @Override
        public void save(ExecutionCheckpointEnvelopeCodec.ExecutionCheckpointValue checkpoint) {
            raw.add(checkpoint);
            data.computeIfAbsent(checkpoint.executionId(), ignored -> new ArrayList<>()).add(checkpoint);
        }

        @Override
        public Optional<ExecutionCheckpointEnvelopeCodec.ExecutionCheckpointValue> loadLatest(String executionId) {
            List<ExecutionCheckpointEnvelopeCodec.ExecutionCheckpointValue> history = data.get(executionId);
            return history == null || history.isEmpty()
                    ? Optional.empty() : Optional.of(history.get(history.size() - 1));
        }

        @Override
        public Optional<ExecutionCheckpointEnvelopeCodec.ExecutionCheckpointValue> load(
                String executionId, String checkpointId) {
            return data.getOrDefault(executionId, List.of()).stream()
                    .filter(value -> value.checkpointId().equals(checkpointId)).findFirst();
        }

        @Override
        public List<ExecutionCheckpointEnvelopeCodec.ExecutionCheckpointValue> history(String executionId) {
            return List.copyOf(data.getOrDefault(executionId, List.of()));
        }

        @Override
        public void delete(String executionId) {
            data.remove(executionId);
        }
    }
}
