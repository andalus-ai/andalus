package tech.kayys.andalus.execution;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import tech.kayys.andalus.agent.AgentContext;
import tech.kayys.andalus.context.ContextProvider;
import tech.kayys.andalus.core.AgentDefinition;
import tech.kayys.andalus.agent.AgentRequest;
import tech.kayys.andalus.execution.cache.ExecutionCache;
import tech.kayys.andalus.execution.context.DefaultRuntimeContextPlanner;
import tech.kayys.andalus.execution.context.RuntimeContextPlanner;
import tech.kayys.andalus.memory.manager.MemoryManager;
import tech.kayys.andalus.execution.event.EventLedger;
import tech.kayys.andalus.execution.AgentToolExecutionContext;
import tech.kayys.andalus.execution.AgentToolExecutionContext.PrincipalType;
import tech.kayys.andalus.execution.governance.ToolBudget;
import tech.kayys.andalus.execution.governance.ToolBudgetLedger;
import tech.kayys.andalus.provider.DefaultModelRouter;
import tech.kayys.andalus.provider.ModelRouter;
import tech.kayys.andalus.provider.Provider;

/**
 * Service for creating and managing {@link AgentExecution} instances.
 *
 * <p>Phase 4: Wires in CDI-managed {@link Provider}, {@link ModelRouter},
 * {@link RuntimeContextPlanner}, {@link MemoryManager}, and {@link ExecutionCache}
 * instances, passing them into {@link DefaultAgentExecution} so the kernel is
 * fully dependency-injected.</p>
 */
@ApplicationScoped
public class AgentExecutionService {

    @Inject
    CheckpointStore checkpointStore;

    @Inject
    AgentToolExecutor toolExecutor;

    @Inject
    ToolBudgetLedger toolBudgetLedger;

    /** All CDI-managed {@link Provider} beans discovered at startup. */
    @Inject
    Instance<Provider> providerInstances;

    /** Optional execution cache. */
    @Inject
    Instance<ExecutionCache> executionCacheInstances;

    /**
     * Optional model router — CDI-discovered; falls back to {@link DefaultModelRouter}
     * if none is configured.
     */
    @Inject
    Instance<ModelRouter> modelRouterInstances;

    /**
     * Optional runtime context planner — CDI-discovered; falls back to
     * {@link DefaultRuntimeContextPlanner} if none is configured.
     */
    @Inject
    Instance<RuntimeContextPlanner> contextPlannerInstances;

    /**
     * Optional memory manager — CDI-discovered; may be absent in minimal deployments.
     */
    @Inject
    Instance<MemoryManager> memoryManagerInstances;

    /**
     * All CDI-managed {@link ContextProvider} beans for context assembly.
     */
    @Inject
    Instance<ContextProvider> contextProviderInstances;

    /**
     * Phase 5: Optional Event Ledger — CDI-discovered; absent in test/minimal deployments.
     */
    @Inject
    Instance<EventLedger> eventLedgerInstances;

    /**
     * P0.2: Optional Execution Checkpoint Store — CDI-discovered.
     */
    @Inject
    Instance<ExecutionCheckpointStore> executionCheckpointStoreInstances;

    // ------------------------------------------------------------------
    // Factory
    // ------------------------------------------------------------------

    public AgentExecution create(AgentDefinition agent, AgentRequest request, ExecutionBudget budget) {
        ExecutionIdentity identity = ExecutionIdentity.create();

        AgentContext agentContext = AgentContext.builder()
            .id(new tech.kayys.andalus.identity.ResourceId.ExecutionId(
                    new tech.kayys.andalus.extension.Id(java.util.UUID.fromString(identity.executionId()))))
            .request(request)
            .build();

        List<Provider> providers = new ArrayList<>();
        if (providerInstances != null) {
            providerInstances.forEach(providers::add);
        }

        ExecutionCache cache = resolveCache();
        ExecutionBudget effectiveBudget = budget != null ? budget : ExecutionBudget.balanced();
        AgentToolExecutionContext toolContext = createToolExecutionContext(identity, agent, null, null, effectiveBudget);

        return new DefaultAgentExecution(
            identity.executionId(),
            agent,
            agentContext,
            effectiveBudget,
            checkpointStore,
            resolveExecutionCheckpointStore(),
            toolExecutor,
            providers,
            resolveModelRouter(),
            resolveContextPlanner(),
            resolveMemoryManager(),
            cache,
            null, // tenantId — null in standalone mode
            null, // userId   — null in standalone mode
            resolveEventLedger(),
            toolContext
        );
    }

    /** Convenience overload using the {@code balanced} profile. */
    public AgentExecution create(AgentDefinition agent, AgentRequest request) {
        return create(agent, request, ExecutionBudget.balanced());
    }

    // ------------------------------------------------------------------
    // Resume / approval
    // ------------------------------------------------------------------

    public AgentExecution resume(String executionId) {
        java.util.Optional<AgentContext> contextOpt = checkpointStore.load(executionId);
        if (contextOpt.isEmpty()) {
            throw new IllegalArgumentException("Execution not found: " + executionId);
        }

        ExecutionIdentity identity = new ExecutionIdentity(executionId, executionId, null);
        AgentToolExecutionContext toolContext = createToolExecutionContext(identity, null, null, null, ExecutionBudget.balanced());
        DefaultAgentExecution execution = new DefaultAgentExecution(
            executionId,
            null,
            contextOpt.get(),
            ExecutionBudget.balanced(),
            checkpointStore,
            resolveExecutionCheckpointStore(),
            toolExecutor,
            List.of(),
            resolveModelRouter(),
            resolveContextPlanner(),
            resolveMemoryManager(),
            resolveCache(),
            null, null,
            resolveEventLedger(),
            toolContext
        );
        execution.resume();
        return execution;
    }

    public void approve(String executionId, AgentDecision decision) {
        AgentExecution execution = resume(executionId);
        if (decision instanceof AgentDecision.WaitForApproval) {
            execution.execute();
        }
    }

    AgentToolExecutionContext createToolExecutionContext(
            ExecutionIdentity identity, AgentDefinition agent, String tenantId,
            String userId, ExecutionBudget executionBudget) {
        String agentId = agent != null ? agent.id().value().asString() : "unknown-agent";
        PrincipalType principalType = userId != null && !userId.isBlank()
                ? PrincipalType.USER : PrincipalType.ANONYMOUS;
        ToolBudget configured = toolBudgetLedger != null
                ? toolBudgetLedger.budgetFor(tenantId, userId)
                : ToolBudget.unlimited();
        ToolBudget executionBudgetForTools = new ToolBudget(
                tenantId, userId, configured.maxCalls(), configured.maxCostUsd(), configured.maxDurationMs());
        return new AgentToolExecutionContext(
                identity.executionId(), identity.correlationId(), identity.parentExecutionId(),
                tenantId, userId, agentId, principalType,
                principalType == PrincipalType.USER ? userId : null,
                java.util.Set.of(), executionBudget, executionBudgetForTools,
                agent != null ? agent.constraints() : java.util.Map.of(),
                java.util.Map.of(), java.time.Instant.now());
    }

    // ------------------------------------------------------------------
    // Internal resolver helpers
    // ------------------------------------------------------------------

    private ExecutionCache resolveCache() {
        if (executionCacheInstances == null || executionCacheInstances.isUnsatisfied()) {
            return null;
        }
        return executionCacheInstances.get();
    }

    private ModelRouter resolveModelRouter() {
        if (modelRouterInstances == null || modelRouterInstances.isUnsatisfied()) {
            return new DefaultModelRouter();
        }
        return modelRouterInstances.get();
    }

    private RuntimeContextPlanner resolveContextPlanner() {
        if (contextPlannerInstances == null || contextPlannerInstances.isUnsatisfied()) {
            return new DefaultRuntimeContextPlanner();
        }
        return contextPlannerInstances.get();
    }

    private MemoryManager resolveMemoryManager() {
        if (memoryManagerInstances == null || memoryManagerInstances.isUnsatisfied()) {
            return null;
        }
        return memoryManagerInstances.get();
    }

    private EventLedger resolveEventLedger() {
        if (eventLedgerInstances == null || eventLedgerInstances.isUnsatisfied()) {
            return null;
        }
        return eventLedgerInstances.get();
    }

    private ExecutionCheckpointStore resolveExecutionCheckpointStore() {
        if (executionCheckpointStoreInstances == null || executionCheckpointStoreInstances.isUnsatisfied()) {
            return null;
        }
        return executionCheckpointStoreInstances.get();
    }
}
