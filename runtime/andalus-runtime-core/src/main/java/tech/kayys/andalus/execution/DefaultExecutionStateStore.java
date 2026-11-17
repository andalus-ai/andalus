package tech.kayys.andalus.execution;

import tech.kayys.andalus.agent.AgentContext;
import tech.kayys.andalus.agent.AgentResponse;
import tech.kayys.andalus.execution.event.EventLedger;
import tech.kayys.andalus.execution.event.ExecutionEvent;
import tech.kayys.andalus.execution.event.ExecutionEventType;
import tech.kayys.andalus.execution.lifecycle.ExecutionSemantics;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation of ExecutionStateStore.
 */
public class DefaultExecutionStateStore implements ExecutionStateStore {

    private final CheckpointStore checkpointStore;
    private final EventLedger eventLedger;
    private final ExecutionCheckpointStore executionCheckpointStore;
    private final Map<String, AgentExecutionState> states = new ConcurrentHashMap<>();
    private final AtomicLong eventSeq = new AtomicLong();

    public DefaultExecutionStateStore(CheckpointStore checkpointStore, EventLedger eventLedger) {
        this(checkpointStore, eventLedger, new InMemoryExecutionCheckpointStore());
    }

    public DefaultExecutionStateStore(
            CheckpointStore checkpointStore,
            EventLedger eventLedger,
            ExecutionCheckpointStore executionCheckpointStore) {
        this.checkpointStore = checkpointStore;
        this.eventLedger = eventLedger;
        this.executionCheckpointStore = executionCheckpointStore != null
                ? executionCheckpointStore
                : new InMemoryExecutionCheckpointStore();
    }

    @Override
    public AgentExecutionState get(String executionId) {
        return states.computeIfAbsent(executionId, this::newInitialState);
    }

    @Override
    public AgentExecutionState transition(String executionId, ExecutionPhase phase, Map<String, Object> metadata) {
        final AgentExecutionState[] result = new AgentExecutionState[1];
        states.compute(executionId, (id, current) -> {
            AgentExecutionState actual = current != null ? current : newInitialState(id);
            AgentExecutionState next = actual.withPhase(phase);
            result[0] = next;
            return next;
        });

        if (eventLedger != null) {
            ExecutionEventType eventType = mapPhaseToEvent(phase);
            if (eventType != null) {
                eventLedger.record(ExecutionEvent.of(
                        executionId,
                        eventSeq.getAndIncrement(),
                        eventType,
                        phase.name(),
                        metadata != null ? metadata : Map.of()
                ));
            }
        }
        return result[0];
    }

    @Override
    public AgentExecutionState transitionStatus(
            String executionId,
            ExecutionStatus expectedCurrent,
            ExecutionStatus newStatus) {

        ExecutionLifecycleRules.requireTransition(executionId, expectedCurrent, newStatus);

        final AgentExecutionState[] result = new AgentExecutionState[1];

        states.compute(executionId, (id, current) -> {
            AgentExecutionState actual = current != null ? current : newInitialState(id);

            if (actual.status() != expectedCurrent) {
                throw new InvalidExecutionTransitionException(id, actual.status(), newStatus);
            }

            AgentExecutionState next = copyWithStatus(actual, newStatus);
            result[0] = next;
            return next;
        });

        recordStatusChange(executionId, expectedCurrent, newStatus);

        return result[0];
    }

    @Override
    public AgentExecutionState changeStatus(String executionId, ExecutionStatus status) {
        final AgentExecutionState[] result = new AgentExecutionState[1];
        final ExecutionStatus[] from = new ExecutionStatus[1];

        states.compute(executionId, (id, current) -> {
            AgentExecutionState actual = current != null ? current : newInitialState(id);
            from[0] = actual.status();
            AgentExecutionState next = actual.withStatus(status);
            result[0] = next;
            return next;
        });

        recordStatusChange(executionId, from[0], status);

        return result[0];
    }

    @Override
    public AgentExecutionState incrementAttempt(String executionId) {
        final AgentExecutionState[] result = new AgentExecutionState[1];

        states.compute(executionId, (id, current) -> {
            AgentExecutionState actual = current != null ? current : newInitialState(id);
            AgentExecutionState next = actual.withAttempt(actual.attempt() + 1);
            result[0] = next;
            return next;
        });

        return result[0];
    }

    @Override
    public AgentExecutionState heartbeat(String executionId) {
        final AgentExecutionState[] result = new AgentExecutionState[1];

        states.compute(executionId, (id, current) -> {
            AgentExecutionState actual = current != null ? current : newInitialState(id);
            AgentExecutionState next = actual.heartbeat();
            result[0] = next;
            return next;
        });

        return result[0];
    }

    @Override
    public AgentExecutionState configure(
            String executionId,
            Instant deadline,
            String idempotencyKey,
            ExecutionSemantics semantics) {

        final AgentExecutionState[] result = new AgentExecutionState[1];

        states.compute(executionId, (id, current) -> {
            AgentExecutionState actual = current != null ? current : newInitialState(id);
            AgentExecutionState next = new AgentExecutionState(
                    actual.executionId(),
                    actual.status(),
                    actual.phase(),
                    actual.attempt(),
                    actual.iteration(),
                    actual.checkpointId(),
                    actual.lastEventId(),
                    actual.modelId(),
                    actual.inputTokens(),
                    actual.outputTokens(),
                    actual.startedAt(),
                    Instant.now(),
                    deadline,
                    Instant.now(),
                    idempotencyKey,
                    semantics
            );
            result[0] = next;
            return next;
        });

        return result[0];
    }

    @Override
    public ExecutionCheckpoint checkpoint(String executionId, AgentContext context) {
        return checkpoint(executionId, context, Map.of());
    }

    @Override
    public ExecutionCheckpoint checkpoint(
            String executionId, AgentContext context, Map<String, Object> metadata) {
        AgentExecutionState state = get(executionId);

        ExecutionCheckpoint checkpoint = ExecutionCheckpoint.of(
                executionId,
                state.status(),
                state.phase(),
                state.attempt(),
                state.iteration(),
                context,
                metadata
        );

        if (executionCheckpointStore != null) {
            executionCheckpointStore.save(checkpoint);
        }

        states.compute(executionId, (id, current) -> {
            AgentExecutionState actual = current != null ? current : newInitialState(id);
            return new AgentExecutionState(
                    actual.executionId(),
                    actual.status(),
                    actual.phase(),
                    actual.attempt(),
                    actual.iteration(),
                    checkpoint.checkpointId().value(),
                    actual.lastEventId(),
                    actual.modelId(),
                    actual.inputTokens(),
                    actual.outputTokens(),
                    actual.startedAt(),
                    Instant.now(),
                    actual.deadline(),
                    actual.lastHeartbeatAt(),
                    actual.idempotencyKey(),
                    actual.executionSemantics()
            );
        });

        if (eventLedger != null) {
            eventLedger.record(ExecutionEvent.of(
                    executionId,
                    eventSeq.getAndIncrement(),
                    ExecutionEventType.CHECKPOINT_CREATED,
                    checkpoint.checkpointId().value(),
                    Map.of("phase", state.phase().name(), "status", state.status().name())
            ));
        }

        return checkpoint;
    }

    @Override
    public void checkpoint(AgentExecutionState state) {
        // Legacy marker method
    }

    @Override
    public void complete(String executionId, AgentResponse response) {
        final boolean[] transitioned = new boolean[1];

        states.compute(executionId, (id, current) -> {
            AgentExecutionState actual = current != null ? current : newInitialState(id);

            // Cancellation or an already committed completion wins over a late
            // asynchronous success callback. Do not emit a second completion event.
            if (actual.status() == ExecutionStatus.CANCELLED
                    || actual.status() == ExecutionStatus.COMPLETED) {
                return actual;
            }

            ExecutionLifecycleRules.requireTransition(id, actual.status(), ExecutionStatus.COMPLETED);
            transitioned[0] = true;

            return new AgentExecutionState(
                    id,
                    ExecutionStatus.COMPLETED,
                    ExecutionPhase.COMPLETE,
                    actual.attempt(),
                    actual.iteration(),
                    actual.checkpointId(),
                    actual.lastEventId(),
                    actual.modelId(),
                    actual.inputTokens(),
                    actual.outputTokens(),
                    actual.startedAt(),
                    Instant.now(),
                    actual.deadline(),
                    actual.lastHeartbeatAt(),
                    actual.idempotencyKey(),
                    actual.executionSemantics()
            );
        });

        if (transitioned[0] && eventLedger != null) {
            eventLedger.record(ExecutionEvent.of(
                    executionId,
                    eventSeq.getAndIncrement(),
                    ExecutionEventType.EXECUTION_COMPLETED,
                    ExecutionStatus.COMPLETED.name(),
                    Map.of("success", response != null && response.success())
            ));
        }
    }

    @Override
    public void fail(String executionId, Throwable error) {
        final boolean[] transitioned = new boolean[1];

        states.compute(executionId, (id, current) -> {
            AgentExecutionState actual = current != null ? current : newInitialState(id);

            // A terminal outcome wins. Late provider/tool callbacks must not
            // replace cancellation or successful completion with failure.
            if (actual.status() == ExecutionStatus.CANCELLED
                    || actual.status() == ExecutionStatus.COMPLETED) {
                return actual;
            }

            // Failure is a live lifecycle transition, not an unconditional
            // state assignment. This keeps it consistent with transitionStatus.
            if (actual.status() == ExecutionStatus.FAILED) {
                return actual; // idempotent duplicate failure callback
            }
            ExecutionLifecycleRules.requireTransition(id, actual.status(), ExecutionStatus.FAILED);
            transitioned[0] = true;

            return new AgentExecutionState(
                    id,
                    ExecutionStatus.FAILED,
                    ExecutionPhase.COMPLETE,
                    actual.attempt(),
                    actual.iteration(),
                    actual.checkpointId(),
                    actual.lastEventId(),
                    actual.modelId(),
                    actual.inputTokens(),
                    actual.outputTokens(),
                    actual.startedAt(),
                    Instant.now(),
                    actual.deadline(),
                    actual.lastHeartbeatAt(),
                    actual.idempotencyKey(),
                    actual.executionSemantics()
            );
        });

        if (transitioned[0] && eventLedger != null) {
            eventLedger.record(ExecutionEvent.of(
                    executionId,
                    eventSeq.getAndIncrement(),
                    ExecutionEventType.EXECUTION_FAILED,
                    ExecutionStatus.FAILED.name(),
                    Map.of("error", error != null ? error.getMessage() : "unknown")
            ));
        }
    }

    private AgentExecutionState newInitialState(String executionId) {
        Instant now = Instant.now();
        return new AgentExecutionState(
                executionId,
                ExecutionStatus.PENDING,
                ExecutionPhase.INPUT,
                0,
                0,
                null,
                null,
                null,
                0,
                0,
                now,
                now,
                null,
                now,
                null,
                ExecutionSemantics.AT_LEAST_ONCE
        );
    }

    private AgentExecutionState copyWithStatus(AgentExecutionState state, ExecutionStatus status) {
        return new AgentExecutionState(
                state.executionId(),
                status,
                state.phase(),
                state.attempt(),
                state.iteration(),
                state.checkpointId(),
                state.lastEventId(),
                state.modelId(),
                state.inputTokens(),
                state.outputTokens(),
                state.startedAt(),
                Instant.now(),
                state.deadline(),
                Instant.now(),
                state.idempotencyKey(),
                state.executionSemantics()
        );
    }

    private void recordStatusChange(String executionId, ExecutionStatus from, ExecutionStatus to) {
        if (eventLedger == null || from == to) {
            return;
        }

        ExecutionEventType eventType = switch (to) {
            case RUNNING -> (from == ExecutionStatus.PAUSED)
                    ? ExecutionEventType.EXECUTION_RESUMED
                    : ExecutionEventType.EXECUTION_STARTED;
            case PAUSED -> ExecutionEventType.EXECUTION_PAUSED;
            case COMPLETED -> ExecutionEventType.EXECUTION_COMPLETED;
            case FAILED, ERROR, TIMEOUT -> ExecutionEventType.EXECUTION_FAILED;
            case CANCELLED -> ExecutionEventType.EXECUTION_CANCELLED;
            default -> null;
        };

        if (eventType != null) {
            eventLedger.record(ExecutionEvent.of(
                    executionId,
                    eventSeq.getAndIncrement(),
                    eventType,
                    to.name(),
                    Map.of("from", from != null ? from.name() : "null", "to", to.name())
            ));
        }
    }

    private ExecutionEventType mapPhaseToEvent(ExecutionPhase phase) {
        return switch (phase) {
            case INPUT -> ExecutionEventType.EXECUTION_STARTED;
            case CONTEXT -> ExecutionEventType.CONTEXT_COMPILED;
            case TOOL -> ExecutionEventType.TOOL_REQUESTED;
            case INFERENCE -> ExecutionEventType.MODEL_ROUTING_RESOLVED;
            default -> null;
        };
    }
}
