package tech.kayys.andalus.api.rest.execution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import tech.kayys.andalus.execution.checkpoint.CheckpointStore;
import tech.kayys.andalus.execution.checkpoint.CheckpointStrategy;
import tech.kayys.andalus.execution.core.checkpoint.DefaultCheckpointStrategy;
import tech.kayys.andalus.execution.core.checkpoint.InMemoryExecutionCheckpointStore;
import tech.kayys.andalus.execution.core.environment.DefaultExecutionEnvironmentProvider;
import tech.kayys.andalus.execution.core.lifecycle.DefaultExecutionStateMachine;
import tech.kayys.andalus.execution.core.migration.DefaultMigrationPolicy;
import tech.kayys.andalus.execution.core.policy.DefaultSandboxPolicyEngine;
import tech.kayys.andalus.execution.core.profile.DefaultSandboxProfileRegistry;
import tech.kayys.andalus.execution.core.recovery.DefaultExecutionRecoveryCoordinator;
import tech.kayys.andalus.execution.core.recovery.DefaultRecoveryManager;
import tech.kayys.andalus.execution.core.sandbox.DefaultSandboxManager;
import tech.kayys.andalus.execution.environment.ExecutionEnvironmentProvider;
import tech.kayys.andalus.execution.lifecycle.ExecutionStateMachine;
import tech.kayys.andalus.execution.migration.MigrationPolicy;
import tech.kayys.andalus.execution.policy.SandboxPolicyEngine;
import tech.kayys.andalus.execution.recovery.ExecutionRecoveryCoordinator;
import tech.kayys.andalus.execution.recovery.RecoveryManager;
import tech.kayys.andalus.execution.sandbox.SandboxManager;
import tech.kayys.andalus.execution.sandbox.SandboxSpec;

/**
 * CDI Producer providing default implementations for execution, sandbox,
 * checkpoint, and recovery SPIs.
 */
@ApplicationScoped
public class ExecutionProducer {

    @Produces
    @ApplicationScoped
    public ExecutionStateMachine produceExecutionStateMachine() {
        return new DefaultExecutionStateMachine();
    }

    @Produces
    @ApplicationScoped
    public CheckpointStore produceCheckpointStore() {
        return new InMemoryExecutionCheckpointStore();
    }

    @Produces
    @ApplicationScoped
    public CheckpointStrategy produceCheckpointStrategy() {
        return new DefaultCheckpointStrategy();
    }

    @Produces
    @ApplicationScoped
    public RecoveryManager produceRecoveryManager(CheckpointStore checkpointStore) {
        return new DefaultRecoveryManager(checkpointStore);
    }

    @Produces
    @ApplicationScoped
    public ExecutionRecoveryCoordinator produceExecutionRecoveryCoordinator(RecoveryManager recoveryManager) {
        return new DefaultExecutionRecoveryCoordinator(recoveryManager);
    }

    @Produces
    @ApplicationScoped
    public SandboxManager produceSandboxManager() {
        return new DefaultSandboxManager();
    }

    @Produces
    @ApplicationScoped
    public SandboxPolicyEngine produceSandboxPolicyEngine() {
        return new DefaultSandboxPolicyEngine(new SandboxSpec(null, null, null, null, null, null, null));
    }

    @Produces
    @ApplicationScoped
    public DefaultSandboxProfileRegistry produceSandboxProfileRegistry() {
        return new DefaultSandboxProfileRegistry();
    }

    @Produces
    @ApplicationScoped
    public ExecutionEnvironmentProvider produceExecutionEnvironmentProvider() {
        return new DefaultExecutionEnvironmentProvider();
    }

    @Produces
    @ApplicationScoped
    public MigrationPolicy produceMigrationPolicy() {
        return new DefaultMigrationPolicy();
    }

    @Produces
    @ApplicationScoped
    public tech.kayys.andalus.agent.kernel.interaction.AgentInteractionService produceAgentInteractionService() {
        return tech.kayys.andalus.agent.kernel.interaction.AgentInteractionService.inMemory();
    }
}
