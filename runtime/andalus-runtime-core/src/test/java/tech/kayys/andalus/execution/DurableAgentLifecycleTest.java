package tech.kayys.andalus.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.agent.AgentContext;
import tech.kayys.andalus.agent.AgentRequest;
import tech.kayys.andalus.agent.AgentResponse;
import tech.kayys.andalus.execution.lifecycle.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DurableAgentLifecycleTest {

    private ExecutionCheckpointStore checkpointStore;
    private DefaultExecutionStateStore stateStore;

    @BeforeEach
    void setUp() {
        checkpointStore = new InMemoryExecutionCheckpointStore();
        stateStore = new DefaultExecutionStateStore(null, null, checkpointStore);
    }

    // -------------------------------------------------------------------------
    // 1. Lifecycle Rules & Transitions
    // -------------------------------------------------------------------------

    @Test
    void testValidTransitions() {
        assertTrue(ExecutionLifecycleRules.canTransition(ExecutionStatus.PENDING, ExecutionStatus.RUNNING));
        assertTrue(ExecutionLifecycleRules.canTransition(ExecutionStatus.PENDING, ExecutionStatus.CANCELLED));

        assertTrue(ExecutionLifecycleRules.canTransition(ExecutionStatus.RUNNING, ExecutionStatus.PAUSED));
        assertTrue(ExecutionLifecycleRules.canTransition(ExecutionStatus.RUNNING, ExecutionStatus.COMPLETED));
        assertTrue(ExecutionLifecycleRules.canTransition(ExecutionStatus.RUNNING, ExecutionStatus.FAILED));
        assertTrue(ExecutionLifecycleRules.canTransition(ExecutionStatus.RUNNING, ExecutionStatus.CANCELLED));

        assertTrue(ExecutionLifecycleRules.canTransition(ExecutionStatus.PAUSED, ExecutionStatus.RUNNING));
        assertTrue(ExecutionLifecycleRules.canTransition(ExecutionStatus.PAUSED, ExecutionStatus.CANCELLED));

        assertTrue(ExecutionLifecycleRules.canTransition(ExecutionStatus.FAILED, ExecutionStatus.RUNNING));
        assertTrue(ExecutionLifecycleRules.canTransition(ExecutionStatus.FAILED, ExecutionStatus.CANCELLED));

        // Same status is allowed
        assertTrue(ExecutionLifecycleRules.canTransition(ExecutionStatus.RUNNING, ExecutionStatus.RUNNING));
    }

    @Test
    void testInvalidTransitions() {
        assertFalse(ExecutionLifecycleRules.canTransition(ExecutionStatus.COMPLETED, ExecutionStatus.RUNNING));
        assertFalse(ExecutionLifecycleRules.canTransition(ExecutionStatus.CANCELLED, ExecutionStatus.RUNNING));
        assertFalse(ExecutionLifecycleRules.canTransition(ExecutionStatus.PENDING, ExecutionStatus.PAUSED));
        assertFalse(ExecutionLifecycleRules.canTransition(ExecutionStatus.PENDING, ExecutionStatus.COMPLETED));

        assertThrows(InvalidExecutionTransitionException.class, () ->
                ExecutionLifecycleRules.requireTransition("exec-1", ExecutionStatus.COMPLETED, ExecutionStatus.RUNNING));
    }

    // -------------------------------------------------------------------------
    // 2. Execution State Store CAS Transitions
    // -------------------------------------------------------------------------

    @Test
    void testTransitionStatusCAS() {
        String execId = "exec-cas-1";
        AgentExecutionState initial = stateStore.get(execId);
        assertEquals(ExecutionStatus.PENDING, initial.status());

        // Successful CAS: PENDING -> RUNNING
        AgentExecutionState running = stateStore.transitionStatus(execId, ExecutionStatus.PENDING, ExecutionStatus.RUNNING);
        assertEquals(ExecutionStatus.RUNNING, running.status());

        // Conflicting CAS: expected PENDING but actual is RUNNING -> throws InvalidExecutionTransitionException
        assertThrows(InvalidExecutionTransitionException.class, () ->
                stateStore.transitionStatus(execId, ExecutionStatus.PENDING, ExecutionStatus.PAUSED));

        // Successful CAS: RUNNING -> PAUSED
        AgentExecutionState paused = stateStore.transitionStatus(execId, ExecutionStatus.RUNNING, ExecutionStatus.PAUSED);
        assertEquals(ExecutionStatus.PAUSED, paused.status());

        // Successful CAS: PAUSED -> RUNNING
        AgentExecutionState resumed = stateStore.transitionStatus(execId, ExecutionStatus.PAUSED, ExecutionStatus.RUNNING);
        assertEquals(ExecutionStatus.RUNNING, resumed.status());
    }

    @Test
    void testFailureUsesLifecycleTransitionRulesAndPreservesTerminalOutcomes() {
        String pendingId = "exec-fail-pending";
        stateStore.get(pendingId);
        assertThrows(InvalidExecutionTransitionException.class,
                () -> stateStore.fail(pendingId, new RuntimeException("too early")));
        assertEquals(ExecutionStatus.PENDING, stateStore.get(pendingId).status());

        String runningId = "exec-fail-running";
        stateStore.transitionStatus(runningId, ExecutionStatus.PENDING, ExecutionStatus.RUNNING);
        stateStore.fail(runningId, new RuntimeException("provider failed"));
        assertEquals(ExecutionStatus.FAILED, stateStore.get(runningId).status());

        // Duplicate failure callbacks are idempotent.
        stateStore.fail(runningId, new RuntimeException("duplicate callback"));
        assertEquals(ExecutionStatus.FAILED, stateStore.get(runningId).status());

        String cancelledId = "exec-fail-cancelled";
        stateStore.transitionStatus(cancelledId, ExecutionStatus.PENDING, ExecutionStatus.CANCELLED);
        stateStore.fail(cancelledId, new RuntimeException("late callback"));
        assertEquals(ExecutionStatus.CANCELLED, stateStore.get(cancelledId).status());

        String completedId = "exec-fail-completed";
        stateStore.transitionStatus(completedId, ExecutionStatus.PENDING, ExecutionStatus.RUNNING);
        stateStore.transitionStatus(completedId, ExecutionStatus.RUNNING, ExecutionStatus.COMPLETED);
        stateStore.fail(completedId, new RuntimeException("late callback"));
        assertEquals(ExecutionStatus.COMPLETED, stateStore.get(completedId).status());
    }

    @Test
    void testLateAndDuplicateCompletionPreserveTerminalOutcomes() {
        String cancelledId = "exec-complete-cancelled";
        stateStore.transitionStatus(cancelledId, ExecutionStatus.PENDING, ExecutionStatus.CANCELLED);

        // A late success callback after cancellation must not throw or replace CANCELLED.
        assertDoesNotThrow(() -> stateStore.complete(cancelledId,
                AgentResponse.builder().id(cancelledId).success(true).content("late").build()));
        assertEquals(ExecutionStatus.CANCELLED, stateStore.get(cancelledId).status());

        String completedId = "exec-complete-duplicate";
        stateStore.transitionStatus(completedId, ExecutionStatus.PENDING, ExecutionStatus.RUNNING);
        AgentResponse response = AgentResponse.builder()
                .id(completedId).success(true).content("done").build();

        stateStore.complete(completedId, response);
        assertEquals(ExecutionStatus.COMPLETED, stateStore.get(completedId).status());

        // Repeated completion callbacks are idempotent.
        assertDoesNotThrow(() -> stateStore.complete(completedId, response));
        assertEquals(ExecutionStatus.COMPLETED, stateStore.get(completedId).status());
    }

    @Test
    void testChangeStatusAndAttempt() {
        String execId = "exec-attempt-1";
        stateStore.get(execId);

        AgentExecutionState s1 = stateStore.incrementAttempt(execId);
        assertEquals(1, s1.attempt());

        AgentExecutionState s2 = stateStore.incrementAttempt(execId);
        assertEquals(2, s2.attempt());

        AgentExecutionState heartbeat = stateStore.heartbeat(execId);
        assertNotNull(heartbeat.lastHeartbeatAt());
    }

    @Test
    void testConfigureExecution() {
        String execId = "exec-config-1";
        Instant deadline = Instant.now().plusSeconds(3600);
        AgentExecutionState configured = stateStore.configure(
                execId,
                deadline,
                "idemp-key-123",
                ExecutionSemantics.EXACTLY_ONCE
        );

        assertEquals(deadline, configured.deadline());
        assertEquals("idemp-key-123", configured.idempotencyKey());
        assertEquals(ExecutionSemantics.EXACTLY_ONCE, configured.executionSemantics());
    }

    // -------------------------------------------------------------------------
    // 3. Execution Checkpoint Protocol
    // -------------------------------------------------------------------------

    @Test
    void testCheckpointStoreAndProtocol() {
        String execId = "exec-cp-1";
        AgentContext context = AgentContext.builder().build();

        // Checkpoint in PENDING
        ExecutionCheckpoint cp1 = stateStore.checkpoint(execId, context);
        assertNotNull(cp1.checkpointId());
        assertEquals(execId, cp1.executionId());
        assertEquals(ExecutionStatus.PENDING, cp1.status());

        AgentExecutionState state = stateStore.get(execId);
        assertEquals(cp1.checkpointId().value(), state.checkpointId());

        // Verify stored in ExecutionCheckpointStore
        Optional<ExecutionCheckpoint> loaded = checkpointStore.load(execId);
        assertTrue(loaded.isPresent());
        assertEquals(cp1.checkpointId(), loaded.get().checkpointId());

        // Transition to RUNNING and checkpoint again
        stateStore.transitionStatus(execId, ExecutionStatus.PENDING, ExecutionStatus.RUNNING);
        ExecutionCheckpoint cp2 = stateStore.checkpoint(execId, context);

        List<ExecutionCheckpoint> history = checkpointStore.history(execId);
        assertEquals(2, history.size());
        assertEquals(cp1.checkpointId(), history.get(0).checkpointId());
        assertEquals(cp2.checkpointId(), history.get(1).checkpointId());

        // Load by specific checkpoint ID
        Optional<ExecutionCheckpoint> specific = checkpointStore.load(execId, cp1.checkpointId());
        assertTrue(specific.isPresent());
        assertEquals(ExecutionStatus.PENDING, specific.get().status());
    }

    // -------------------------------------------------------------------------
    // 4. Commands and Semantics
    // -------------------------------------------------------------------------

    @Test
    void testExecutionCommands() {
        ExecutionLifecycleCommand pause = new PauseExecution("exec-1");
        assertEquals("exec-1", pause.executionId());

        ExecutionLifecycleCommand resume = new ResumeExecution("exec-1");
        assertEquals("exec-1", resume.executionId());

        ExecutionLifecycleCommand cancel = new CancelExecution("exec-1");
        assertEquals("exec-1", cancel.executionId());

        ExecutionLifecycleCommand retry = new RetryExecution("exec-1");
        assertEquals("exec-1", retry.executionId());

        tech.kayys.andalus.execution.lifecycle.ExecutionLifecycle lifecycle = new tech.kayys.andalus.execution.lifecycle.ExecutionLifecycle("exec-1", ExecutionStatus.RUNNING, 1, Instant.now());
        assertEquals(ExecutionStatus.RUNNING, lifecycle.status());
        assertEquals(1, lifecycle.attempt());
    }

    @Test
    void testIdempotencyValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionIdempotency("", ExecutionSemantics.AT_LEAST_ONCE));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionIdempotency("key", null));

        ExecutionIdempotency idemp = new ExecutionIdempotency("key-1", ExecutionSemantics.EXACTLY_ONCE);
        assertEquals("key-1", idemp.key());
        assertEquals(ExecutionSemantics.EXACTLY_ONCE, idemp.semantics());
    }

    // -------------------------------------------------------------------------
    // 5. DefaultAgentExecution Lifecycle Operations
    // -------------------------------------------------------------------------

    @Test
    void testCancelReconcilesWhenCompletionAlreadyWon() {
        String execId = "exec-cancel-after-complete";
        DefaultAgentExecution execution = new DefaultAgentExecution(
                execId,
                null,
                AgentContext.builder().build(),
                ExecutionBudget.fast(),
                new InMemoryCheckpointStore(),
                null
        );

        execution.stateStore().transitionStatus(
                execId, ExecutionStatus.PENDING, ExecutionStatus.RUNNING);
        execution.stateStore().transitionStatus(
                execId, ExecutionStatus.RUNNING, ExecutionStatus.COMPLETED);

        assertDoesNotThrow(execution::cancel);
        assertEquals(ExecutionStatus.COMPLETED, execution.status());
        assertEquals(ExecutionStatus.COMPLETED, execution.stateStore().get(execId).status());
    }

    @Test
    void testDefaultAgentExecutionLifecycleControls() {
        String execId = "exec-agent-1";
        AgentContext context = AgentContext.builder().build();
        ExecutionBudget budget = ExecutionBudget.fast();
        InMemoryCheckpointStore legacyCheckpointStore = new InMemoryCheckpointStore();

        DefaultAgentExecution execution = new DefaultAgentExecution(
                execId,
                null,
                context,
                budget,
                legacyCheckpointStore,
                checkpointStore,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                "tenant-1",
                "user-1",
                null
        );

        // pause when not RUNNING is a no-op
        execution.pause();
        assertEquals(ExecutionStatus.PENDING, execution.status());

        // resume when not PAUSED is a no-op
        execution.resume();
        assertEquals(ExecutionStatus.PENDING, execution.status());

        // Test reconstruction of execution from PAUSED checkpoint
        String pausedId = "exec-agent-paused";
        ExecutionCheckpoint pausedCp = ExecutionCheckpoint.of(
                pausedId,
                ExecutionStatus.PAUSED,
                ExecutionPhase.TOOL,
                1,
                2,
                context
        );
        checkpointStore.save(pausedCp);
        legacyCheckpointStore.save(pausedId, context);

        DefaultAgentExecution pausedExecution = new DefaultAgentExecution(
                pausedId,
                null,
                context,
                budget,
                legacyCheckpointStore,
                checkpointStore,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                "tenant-1",
                "user-1",
                null
        );
        assertEquals(ExecutionStatus.PAUSED, pausedExecution.status());

        // Resume transitions PAUSED -> RUNNING
        pausedExecution.resume();
        assertEquals(ExecutionStatus.RUNNING, pausedExecution.status());

        // Pause transitions RUNNING -> PAUSED
        pausedExecution.pause();
        assertEquals(ExecutionStatus.PAUSED, pausedExecution.status());
        assertTrue(checkpointStore.load(pausedId).isPresent());
        assertTrue(legacyCheckpointStore.load(pausedId).isPresent());

        // Cancel transitions to CANCELLED and does NOT delete checkpoints
        pausedExecution.cancel();
        assertEquals(ExecutionStatus.CANCELLED, pausedExecution.status());
        assertTrue(checkpointStore.load(pausedId).isPresent(), "Checkpoint must not be deleted on cancellation");
        assertTrue(legacyCheckpointStore.load(pausedId).isPresent(), "Legacy checkpoint must not be deleted on cancellation");
    }
}
