package tech.kayys.andalus.execution;

import tech.kayys.andalus.agent.AgentContext;
import tech.kayys.andalus.agent.AgentResponse;
import tech.kayys.andalus.execution.lifecycle.ExecutionSemantics;

import java.time.Instant;
import java.util.Map;

/**
 * Execution state transition API.
 */
public interface ExecutionStateStore {

    AgentExecutionState get(String executionId);

    AgentExecutionState transition(
        String executionId,
        ExecutionPhase phase,
        Map<String, Object> metadata
    );

    AgentExecutionState transitionStatus(
        String executionId,
        ExecutionStatus expectedCurrent,
        ExecutionStatus newStatus
    );

    AgentExecutionState changeStatus(
        String executionId,
        ExecutionStatus status
    );

    AgentExecutionState incrementAttempt(
        String executionId
    );

    AgentExecutionState heartbeat(
        String executionId
    );

    AgentExecutionState configure(
        String executionId,
        Instant deadline,
        String idempotencyKey,
        ExecutionSemantics semantics
    );

    ExecutionCheckpoint checkpoint(
        String executionId,
        AgentContext context
    );

    /**
     * Checkpoints execution state with immutable metadata such as its owner identity.
     * Implementations that do not support metadata retain source compatibility.
     */
    default ExecutionCheckpoint checkpoint(
        String executionId,
        AgentContext context,
        Map<String, Object> metadata
    ) {
        return checkpoint(executionId, context);
    }

    default void checkpoint(AgentExecutionState state) {
        // Deprecated marker
    }

    void complete(String executionId, AgentResponse response);

    void fail(String executionId, Throwable error);
}
