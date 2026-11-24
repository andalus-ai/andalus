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
import tech.kayys.andalus.execution.governance.quota.ToolBudgetLedger;
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
        return create(agent, request, budget, AgentCallerContext.anonymous(null));
    }

    /**
     * Backwards-compatible caller overload. New transport adapters should use
     * {@link AgentCallerContext} so both roles and permissions remain explicit.
     */
    public AgentExecution create(AgentDefinition agent, AgentRequest request, ExecutionBudget budget,
                                 String tenantId, String userId, java.util.Set<String> roles) {
        return create(agent, request, budget, legacyCallerContext(tenantId, userId, roles));
    }

    /** Creates an execution with a trusted caller snapshot supplied by the transport boundary. */
    public AgentExecution create(AgentDefinition agent, AgentRequest request, ExecutionBudget budget,
                                 AgentCallerContext caller) {
        AgentCallerContext trustedCaller = java.util.Objects.requireNonNull(caller, "caller");
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
        AgentToolExecutionContext toolContext = createToolExecutionContext(
                identity, agent, trustedCaller, effectiveBudget);

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
            trustedCaller.tenantId(),
            trustedCaller.userId(),
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
        return resume(executionId, AgentCallerContext.anonymous(null));
    }

    /** Backwards-compatible resume overload; claims are supplied by the caller. */
    public AgentExecution resume(String executionId, String tenantId, String userId,
                                 java.util.Set<String> roles) {
        return resume(executionId, legacyCallerContext(tenantId, userId, roles));
    }

    /**
     * Resumes an execution using current trusted caller claims. Ownership is
     * checked against the checkpoint; roles and permissions are never loaded
     * from checkpoint metadata.
     */
    public AgentExecution resume(String executionId, AgentCallerContext caller) {
        AgentCallerContext trustedCaller = java.util.Objects.requireNonNull(caller, "caller");
        ExecutionCheckpointStore lifecycleStore = resolveExecutionCheckpointStore();
        java.util.Optional<ExecutionCheckpoint> lifecycleCheckpoint =
                lifecycleStore != null
                        ? lifecycleStore.load(executionId)
                        : java.util.Optional.empty();

        java.util.Optional<AgentContext> contextOpt = lifecycleCheckpoint
                .map(ExecutionCheckpoint::context)
                .or(() -> checkpointStore != null
                        ? checkpointStore.load(executionId)
                        : java.util.Optional.empty());
        if (contextOpt.isEmpty()) {
            throw new IllegalArgumentException("Execution not found: " + executionId);
        }
        java.util.Map<String, Object> checkpointMetadata = lifecycleCheckpoint
                .map(ExecutionCheckpoint::metadata)
                .orElse(java.util.Map.of());

        verifyResumeIdentity(checkpointMetadata, trustedCaller);

        String checkpointAgentId = (String) checkpointMetadata.getOrDefault("agentId", "unknown-agent");
        ExecutionIdentity identity = new ExecutionIdentity(executionId, executionId, null);
        AgentToolExecutionContext toolContext = createToolExecutionContext(
                identity, null, checkpointAgentId, trustedCaller, ExecutionBudget.balanced());
        DefaultAgentExecution execution = new DefaultAgentExecution(
            executionId,
            null,
            contextOpt.get(),
            ExecutionBudget.balanced(),
            checkpointStore,
            lifecycleStore,
            toolExecutor,
            List.of(),
            resolveModelRouter(),
            resolveContextPlanner(),
            resolveMemoryManager(),
            resolveCache(),
            trustedCaller.tenantId(), trustedCaller.userId(),
            resolveEventLedger(),
            toolContext
        );
        execution.resume();
        return execution;
    }

    public void approve(String executionId, AgentDecision decision) {
        approve(executionId, decision, null, null, java.util.Set.of());
    }

    public void approve(String executionId, AgentDecision decision, String tenantId,
                        String userId, java.util.Set<String> roles) {
        requireApprovalLifecycle(decision);
    }

    /**
     * Approval is deliberately fail-closed until a pending tool invocation can
     * be durably suspended, authorized, audited, and resumed exactly once.
     * A normal execution resume is not an approval operation.
     */
    static void requireApprovalLifecycle(AgentDecision decision) {
        if (!(decision instanceof AgentDecision.WaitForApproval)) {
            throw new IllegalArgumentException("Approval requires a WaitForApproval decision");
        }
        throw new UnsupportedOperationException(
                "Human approval lifecycle is not available; the pending tool invocation was not resumed or executed");
    }

    /**
     * Verifies that a resume request presents the exact owner recorded by the
     * authoritative lifecycle checkpoint. Legacy metadata-free checkpoints
     * remain accessible only through the anonymous compatibility path.
     */
    static void verifyResumeIdentity(
            java.util.Map<String, Object> checkpointMetadata, String tenantId, String userId) {
        verifyResumeIdentity(checkpointMetadata, legacyCallerContext(tenantId, userId, java.util.Set.of()));
    }

    static void verifyResumeIdentity(
            java.util.Map<String, Object> checkpointMetadata, AgentCallerContext caller) {
        java.util.Map<String, Object> metadata = checkpointMetadata != null
                ? checkpointMetadata : java.util.Map.of();
        boolean authenticatedOrTenantScoped = caller.principalType() != AgentCallerContext.PrincipalType.ANONYMOUS
                || (caller.tenantId() != null && !caller.tenantId().isBlank());

        if (metadata.isEmpty()) {
            if (authenticatedOrTenantScoped) {
                throw new SecurityException("Execution checkpoint has no ownership metadata");
            }
            return;
        }

        String expectedPrincipalType = caller.principalType().name();
        String expectedPrincipalId = caller.principalId();
        if (!java.util.Objects.equals(caller.tenantId(), metadata.get("tenantId"))
                || !java.util.Objects.equals(caller.userId(), metadata.get("userId"))
                || !java.util.Objects.equals(expectedPrincipalType, metadata.get("principalType"))
                || !java.util.Objects.equals(expectedPrincipalId, metadata.get("principalId"))) {
            throw new SecurityException("Execution belongs to a different caller or tenant");
        }
    }

    private static AgentCallerContext legacyCallerContext(
            String tenantId, String userId, java.util.Set<String> roles) {
        if (userId == null || userId.isBlank()) {
            if (roles != null && !roles.isEmpty()) {
                throw new IllegalArgumentException("roles require an authenticated userId");
            }
            return AgentCallerContext.anonymous(tenantId);
        }
        return AgentCallerContext.user(tenantId, userId, roles, java.util.Set.of());
    }

    AgentToolExecutionContext createToolExecutionContext(
            ExecutionIdentity identity, AgentDefinition agent, String tenantId,
            String userId, java.util.Set<String> roles, ExecutionBudget executionBudget) {
        return createToolExecutionContext(identity, agent,
                legacyCallerContext(tenantId, userId, roles), executionBudget);
    }

    AgentToolExecutionContext createToolExecutionContext(
            ExecutionIdentity identity, AgentDefinition agent,
            AgentCallerContext caller, ExecutionBudget executionBudget) {
        String agentId = agent != null ? agent.id().value().asString() : "unknown-agent";
        return createToolExecutionContext(identity, agent, agentId, caller, executionBudget);
    }

    private AgentToolExecutionContext createToolExecutionContext(
            ExecutionIdentity identity, AgentDefinition agent, String agentId,
            AgentCallerContext caller, ExecutionBudget executionBudget) {
        AgentToolExecutionContext.PrincipalType principalType =
                AgentToolExecutionContext.PrincipalType.valueOf(caller.principalType().name());
        ToolBudget configured = toolBudgetLedger != null
                ? toolBudgetLedger.budgetFor(caller.tenantId(), caller.userId())
                : ToolBudget.unlimited();
        ToolBudget executionBudgetForTools = new ToolBudget(
                caller.tenantId(), caller.userId(), configured.maxCalls(),
                configured.maxCostUsd(), configured.maxDurationMs());
        return new AgentToolExecutionContext(
                identity.executionId(), identity.correlationId(), identity.parentExecutionId(),
                caller.tenantId(), caller.userId(), agentId, principalType,
                caller.principalId(), caller.roles(), caller.permissions(),
                executionBudget, executionBudgetForTools,
                agent != null ? agent.constraints() : java.util.Map.of(),
                caller.attributes(), java.time.Instant.now());
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
