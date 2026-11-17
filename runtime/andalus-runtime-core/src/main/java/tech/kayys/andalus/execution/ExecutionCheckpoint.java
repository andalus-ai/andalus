package tech.kayys.andalus.execution;

import tech.kayys.andalus.agent.AgentContext;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A lifecycle checkpoint for an agent execution.
 *
 * <p>The checkpoint combines the agent context snapshot with the
 * execution position required to reason about recovery.</p>
 */
public record ExecutionCheckpoint(
    ExecutionCheckpointId checkpointId,
    String executionId,
    ExecutionStatus status,
    ExecutionPhase phase,
    int attempt,
    int iteration,
    AgentContext context,
    Instant createdAt,
    Map<String, Object> metadata
) {

    public ExecutionCheckpoint {
        Objects.requireNonNull(checkpointId, "checkpointId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(createdAt, "createdAt");

        metadata = metadata == null
            ? Map.of()
            : Map.copyOf(metadata);
    }

    public static ExecutionCheckpoint of(
        String executionId,
        ExecutionStatus status,
        ExecutionPhase phase,
        int attempt,
        int iteration,
        AgentContext context
    ) {
        return of(executionId, status, phase, attempt, iteration, context, Map.of());
    }

    public static ExecutionCheckpoint of(
        String executionId,
        ExecutionStatus status,
        ExecutionPhase phase,
        int attempt,
        int iteration,
        AgentContext context,
        Map<String, Object> metadata
    ) {
        return new ExecutionCheckpoint(
            ExecutionCheckpointId.create(),
            executionId,
            status,
            phase,
            attempt,
            iteration,
            context,
            Instant.now(),
            metadata
        );
    }
}
