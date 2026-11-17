package tech.kayys.andalus.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import tech.kayys.andalus.agent.Agent;
import tech.kayys.andalus.agent.AgentContext;
import tech.kayys.andalus.agent.AgentRequest;
import tech.kayys.andalus.agent.AgentResponse;
import tech.kayys.andalus.agent.PermissionDecision;
import tech.kayys.andalus.agent.AndalusAgentListener;
import tech.kayys.andalus.agent.builder.AgentBuilder;
import tech.kayys.andalus.context.ContextProvider;
import tech.kayys.andalus.core.AgentDefinition;
import tech.kayys.andalus.execution.cache.ExecutionCache;
import tech.kayys.andalus.execution.cache.ExecutionCacheEntry;
import tech.kayys.andalus.execution.AgentToolExecutionContext;
import tech.kayys.andalus.execution.AgentToolExecutionContext.PrincipalType;
import tech.kayys.andalus.execution.governance.ToolBudget;
import tech.kayys.andalus.execution.context.RuntimeContextPlan;
import tech.kayys.andalus.execution.context.RuntimeContextPlanner;
import tech.kayys.andalus.execution.context.DefaultRuntimeContextPlanner;
import tech.kayys.andalus.execution.event.EventLedger;
import tech.kayys.andalus.execution.event.ExecutionEvent;
import tech.kayys.andalus.execution.event.ExecutionEventType;
import tech.kayys.andalus.provider.Provider;
import tech.kayys.andalus.provider.routing.InferencePlan;
import tech.kayys.andalus.provider.routing.InferencePolicy;
import tech.kayys.andalus.provider.routing.InferenceRequirements;
import tech.kayys.andalus.provider.routing.ModelRoutingTelemetry;
import tech.kayys.andalus.tool.Tool;
import tech.kayys.andalus.tool.ToolResult;

/**
 * Default implementation of {@link AgentExecution}.
 *
 * <p>Phase 4 enhancement: instead of the Phase 1 stub response, this class now
 * builds a real {@code ReActAgent} from the {@link AgentDefinition}, resolves a
 * {@link Provider}, and drives the full ReAct loop asynchronously.  The streaming
 * callbacks are bridged into a {@link CompletableFuture} that is returned to the
 * caller.</p>
 */
public class DefaultAgentExecution implements AgentExecution {

    private static final Logger LOGGER = Logger.getLogger(DefaultAgentExecution.class.getName());
    private static final Executor AGENT_POOL =
        Executors.newVirtualThreadPerTaskExecutor();

    private final String id;
    private final AgentDefinition agent;
    private final AgentContext agentContext;
    private final ExecutionBudget budget;
    private final CheckpointStore checkpointStore;
    private final ExecutionCheckpointStore executionCheckpointStore;
    private final AgentToolExecutor toolExecutor;
    /** Providers resolved from CDI — may be empty when running without a provider. */
    private final List<Provider> providers;
    
    // Phase 4 components (fully CDI-injected via AgentExecutionService)
    private final tech.kayys.andalus.provider.ModelRouter modelRouter;
    private final RuntimeContextPlanner contextPlanner;
    private final tech.kayys.andalus.memory.manager.MemoryManager memoryManager;

    /** Execution-scoped cache — null when caching is not configured. */
    private final ExecutionCache executionCache;

    /** Tenant / user context for cache key isolation. */
    private final String tenantId;
    private final String userId;
    private final AgentToolExecutionContext toolExecutionContext;

    // Phase 5: Event Ledger — null if not configured (graceful degradation)
    private final EventLedger eventLedger;
    private final ExecutionStateStore stateStore;

    /** Monotonic event sequence counter for this execution. */
    private final java.util.concurrent.atomic.AtomicLong eventSeq = new java.util.concurrent.atomic.AtomicLong();

    private volatile ExecutionStatus status;

    /** One cooperative cancellation signal shared with the ReAct loop and tool executor. */
    private final tech.kayys.andalus.tool.CancellationToken cancellationToken =
            tech.kayys.andalus.tool.CancellationToken.create();

    /** Caller-facing future, completed exceptionally as soon as cancellation wins. */
    private volatile CompletableFuture<AgentResponse> activeFuture;

    public DefaultAgentExecution(
        String id,
        AgentDefinition agent,
        AgentContext agentContext,
        ExecutionBudget budget,
        CheckpointStore checkpointStore,
        AgentToolExecutor toolExecutor,
        List<Provider> providers,
        tech.kayys.andalus.provider.ModelRouter modelRouter,
        RuntimeContextPlanner contextPlanner,
        tech.kayys.andalus.memory.manager.MemoryManager memoryManager,
        ExecutionCache executionCache,
        String tenantId,
        String userId
    ) {
        this(id, agent, agentContext, budget, checkpointStore, null, toolExecutor,
             providers, modelRouter, contextPlanner, memoryManager,
             executionCache, tenantId, userId, null);
    }

    /** Full constructor including Event Ledger. */
    public DefaultAgentExecution(
        String id,
        AgentDefinition agent,
        AgentContext agentContext,
        ExecutionBudget budget,
        CheckpointStore checkpointStore,
        AgentToolExecutor toolExecutor,
        List<Provider> providers,
        tech.kayys.andalus.provider.ModelRouter modelRouter,
        RuntimeContextPlanner contextPlanner,
        tech.kayys.andalus.memory.manager.MemoryManager memoryManager,
        ExecutionCache executionCache,
        String tenantId,
        String userId,
        EventLedger eventLedger
    ) {
        this(id, agent, agentContext, budget, checkpointStore, null, toolExecutor,
             providers, modelRouter, contextPlanner, memoryManager,
             executionCache, tenantId, userId, eventLedger);
    }

    /** Full constructor including ExecutionCheckpointStore and Event Ledger. */
    public DefaultAgentExecution(
        String id,
        AgentDefinition agent,
        AgentContext agentContext,
        ExecutionBudget budget,
        CheckpointStore checkpointStore,
        ExecutionCheckpointStore executionCheckpointStore,
        AgentToolExecutor toolExecutor,
        List<Provider> providers,
        tech.kayys.andalus.provider.ModelRouter modelRouter,
        RuntimeContextPlanner contextPlanner,
        tech.kayys.andalus.memory.manager.MemoryManager memoryManager,
        ExecutionCache executionCache,
        String tenantId,
        String userId,
        EventLedger eventLedger
    ) {
        this(id, agent, agentContext, budget, checkpointStore, executionCheckpointStore,
             toolExecutor, providers, modelRouter, contextPlanner, memoryManager,
             executionCache, tenantId, userId, eventLedger,
             defaultToolExecutionContext(id, agent, tenantId, userId, budget));
    }

    /** Full constructor with immutable tool execution identity and budget. */
    public DefaultAgentExecution(
        String id,
        AgentDefinition agent,
        AgentContext agentContext,
        ExecutionBudget budget,
        CheckpointStore checkpointStore,
        ExecutionCheckpointStore executionCheckpointStore,
        AgentToolExecutor toolExecutor,
        List<Provider> providers,
        tech.kayys.andalus.provider.ModelRouter modelRouter,
        RuntimeContextPlanner contextPlanner,
        tech.kayys.andalus.memory.manager.MemoryManager memoryManager,
        ExecutionCache executionCache,
        String tenantId,
        String userId,
        EventLedger eventLedger,
        AgentToolExecutionContext toolExecutionContext
    ) {
        this.id = id;
        this.agent = agent;
        this.agentContext = agentContext;
        this.budget = budget;
        this.checkpointStore = checkpointStore;
        this.executionCheckpointStore = executionCheckpointStore != null
                ? executionCheckpointStore
                : new InMemoryExecutionCheckpointStore();
        this.toolExecutor = toolExecutor;
        this.providers = providers != null ? new ArrayList<>(providers) : new ArrayList<>();
        
        // Phase 4: All components are now CDI-injected; fall back to defaults only here
        this.modelRouter    = modelRouter    != null ? modelRouter    : new tech.kayys.andalus.provider.DefaultModelRouter();
        this.contextPlanner = contextPlanner != null ? contextPlanner : new DefaultRuntimeContextPlanner();
        this.memoryManager  = memoryManager; // null if memory is not configured
        this.executionCache = executionCache; // null if caching is not configured
        this.tenantId       = tenantId;
        this.userId         = userId;
        this.toolExecutionContext = java.util.Objects.requireNonNull(toolExecutionContext, "toolExecutionContext");
        // Phase 5: Event Ledger
        this.eventLedger    = eventLedger;   // null means events are not persisted
        this.stateStore     = new DefaultExecutionStateStore(checkpointStore, eventLedger, this.executionCheckpointStore);
        var cp = this.executionCheckpointStore.load(id);
        if (cp.isPresent()) {
            this.status = cp.get().status();
            this.stateStore.changeStatus(id, this.status);
        } else {
            AgentExecutionState existingState = this.stateStore.get(id);
            this.status = existingState != null ? existingState.status() : ExecutionStatus.PENDING;
        }
    }

    /** Backwards-compatible constructor without providers (integration tests, resume). */
    public DefaultAgentExecution(
        String id,
        AgentDefinition agent,
        AgentContext agentContext,
        ExecutionBudget budget,
        CheckpointStore checkpointStore,
        AgentToolExecutor toolExecutor
    ) {
        this(id, agent, agentContext, budget,
             checkpointStore, null, toolExecutor, List.of(), null, null, null, null, null, null, null);
    }

    @Override
    public String id() { return id; }

    @Override
    public AgentContext agentContext() { return agentContext; }

    @Override
    public ExecutionStatus status() { return status; }

    public ExecutionStateStore stateStore() { return stateStore; }

    public ExecutionCheckpointStore executionCheckpointStore() { return executionCheckpointStore; }

    // -------------------------------------------------------------------------
    // Core execution — drives the ReAct loop
    // -------------------------------------------------------------------------

    @Override
    public CompletionStage<AgentResponse> execute() {
        stateStore.transitionStatus(id, ExecutionStatus.PENDING, ExecutionStatus.RUNNING);
        this.status = ExecutionStatus.RUNNING;

        // Phase 5: Record execution start
        stateStore.transition(id, ExecutionPhase.INPUT,
            java.util.Map.of("agentId", id, "tenantId", tenantId != null ? tenantId : "*"));

        CompletableFuture<AgentResponse> future = new CompletableFuture<>();
        this.activeFuture = future;
        if (cancellationToken.isCancellationRequested()) {
            completeCancelled(future);
            return future;
        }

        AGENT_POOL.execute(() -> {
            try {
                runAgentLoop(future);
            } catch (Throwable t) {
                stateStore.fail(id, t);
                AgentExecutionState outcome = stateStore.get(id);
                this.status = outcome.status();
                if (outcome.status() == ExecutionStatus.CANCELLED) {
                    completeCancelled(future);
                } else if (outcome.status() == ExecutionStatus.FAILED
                        || outcome.status() == ExecutionStatus.ERROR
                        || outcome.status() == ExecutionStatus.TIMEOUT) {
                    future.completeExceptionally(t);
                }
            }
        });

        return future;
    }

    private void runAgentLoop(CompletableFuture<AgentResponse> future) {
        throwIfCancellationRequested();

        // Derive the prompt from AgentRequest or fall back to empty string.
        AgentRequest request = agentContext.request();
        String prompt = (request != null && request.content() != null)
            ? request.content()
            : "";

        if (prompt.isBlank()) {
            stubComplete(future, "Empty prompt; returning stub response.");
            return;
        }

        // --- Phase 3: Memory Manager ---
        throwIfCancellationRequested();
        stateStore.transition(id, ExecutionPhase.MEMORY, java.util.Map.of());
        if (memoryManager != null) {
            try {
                String recalledMemory = memoryManager.recallContext(prompt).toCompletableFuture().join();
                if (recalledMemory != null && !recalledMemory.isBlank()) {
                    prompt = recalledMemory + "\n\n" + prompt;
                    stateStore.transition(id, ExecutionPhase.MEMORY,
                        java.util.Map.of("chars", recalledMemory.length()));
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Memory recall failed", e);
            }
        }

        throwIfCancellationRequested();

        // --- Phase 3: Model Router & Inference Planning ---
        stateStore.transition(id, ExecutionPhase.INFERENCE, java.util.Map.of());
        Provider provider = null;
        InferencePlan inferencePlan = null;
        final long inferenceStartTime = System.currentTimeMillis();
        try {
            long contextTokens = budget != null ? budget.contextTokens() : 8192;
            boolean requiresToolCalling = (toolExecutor instanceof AgentToolExecutor.ToolAware ta && !ta.availableTools().isEmpty());
            boolean requiresReasoning = (agent != null && agent.reasoner() != null);
            InferenceRequirements requirements = InferenceRequirements.of(
                    java.util.Set.of(tech.kayys.andalus.resource.Modality.TEXT),
                    requiresToolCalling,
                    requiresReasoning,
                    contextTokens
            );
            InferencePolicy policy = mapBudgetToPolicy(budget);

            inferencePlan = modelRouter.plan(request, agent, requirements, policy, providers);
            provider = inferencePlan.selectedProvider();
            final String providerName = provider.getClass().getSimpleName();
            final String modelName = inferencePlan.selectedModel();

            stateStore.transition(id, ExecutionPhase.INFERENCE,
                java.util.Map.of(
                    "provider", providerName,
                    "model", modelName,
                    "reason", inferencePlan.decisionReason() != null ? inferencePlan.decisionReason() : "",
                    "estimatedCost", String.format("$%.4f", inferencePlan.estimatedCost()),
                    "fallbacks", String.valueOf(inferencePlan.fallbackTargets().size())
                ));
        } catch (Exception e) {
            stateStore.fail(id, new RuntimeException("Routing failed: " + e.getMessage(), e));
            stubComplete(future, "Routing failed: " + e.getMessage());
            return;
        }
        final InferencePlan activePlan = inferencePlan;

        throwIfCancellationRequested();

        // --- Phase 4: Runtime Context Planner ---
        stateStore.transition(id, ExecutionPhase.CONTEXT, java.util.Map.of());
        if (contextPlanner != null) {
            try {
                RuntimeContextPlan ctxPlan = contextPlanner.planContext(
                    agentContext, budget, List.of(), prompt
                );
                tech.kayys.andalus.context.ContextData cd = ctxPlan.getContextData();
                if (cd != null && !cd.isEmpty()) {
                    StringBuilder ctx = new StringBuilder();
                    if (!cd.documents().isEmpty()) {
                        cd.documents().forEach(doc -> {
                            if (doc != null && doc.content() != null) ctx.append(doc.content()).append("\n");
                        });
                    }
                    if (cd.knowledge() != null && !cd.knowledge().isEmpty()) {
                        ctx.append("### Relevant Knowledge Evidence:\n");
                        cd.knowledge().forEach(k -> {
                            if (k != null) ctx.append("- ").append(k.toString()).append("\n");
                        });
                    }
                    if (!ctx.isEmpty()) prompt = ctx + "\n" + prompt;
                }
                stateStore.transition(id, ExecutionPhase.CONTEXT,
                    java.util.Map.of(
                        "tokenUsage",  ctxPlan.getTokenUsage(),
                        "providers",   ctxPlan.getContributingProviders().toString()
                    ));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Context planning failed, continuing with raw prompt", e);
            }
        }

        throwIfCancellationRequested();

        // Resolve tools from the tool executor if available.
        List<Tool> tools = new ArrayList<>();
        if (toolExecutor instanceof AgentToolExecutor.ToolAware ta) {
            tools.addAll(ta.availableTools());
        }

        // Extract optional system prompt from agent metadata.
        String systemPrompt = (agent != null && agent.goal() != null)
            ? agent.goal()
            : "You are a helpful AI assistant.";

        // Policy bridge: routes tool calls through the executor pipeline
        tech.kayys.andalus.agent.react.BaseReActAgent.ToolExecutorBridge policyBridge =
            (toolExecutor != null)
            ? (invocation, directFallback) -> {
                AgentDecision decision = toolExecutor.execute(invocation, toolExecutionContext, cancellationToken)
                    .toCompletableFuture().get();
                return switch (decision) {
                    case AgentDecision.ToolCompleted tc -> tc.result();
                    case AgentDecision.ExecuteTool et   -> directFallback.get();
                    case AgentDecision.Fail f           -> {
                        throw new RuntimeException(f.error());
                    }
                    default -> directFallback.get();
                };
              }
            : null;

        // Checkpoint bridge: persists agent context before/after each model step.
        tech.kayys.andalus.agent.react.BaseReActAgent.CheckpointBridge cpBridge =
            (execId, ctx) -> {
                AgentContext snapshot = ctx != null ? ctx : agentContext;
                if (snapshot == null) {
                    return;
                }
                if (stateStore != null) {
                    stateStore.checkpoint(execId, snapshot);
                }
                if (checkpointStore != null) {
                    checkpointStore.save(execId, snapshot);
                }
            };

        // Build the ReActAgent.
        Agent reactAgent = AgentBuilder.create("react")
            .withProvider(provider)
            .withSystemPrompt(systemPrompt)
            .withTools(tools)
            .withAutoApproveTools(true)
            .withToolExecutor(policyBridge)
            .withCancellationToken(cancellationToken)
            .withCheckpointBridge(cpBridge, id)
            .build();

        // Buffer accumulated text for the final response content.
        StringBuilder contentBuffer = new StringBuilder();

        // Drive the loop via AndalusAgentListener callbacks.
        reactAgent.send(prompt, new AndalusAgentListener() {

            @Override
            public void onTextDelta(String text) {
                contentBuffer.append(text);
            }

            @Override
            public void onToolCallStart(String callId, String name) {
                // No buffering needed — tool execution handled inside ReActAgent.
            }

            @Override
            public void onToolCallReady(String callId, String name, java.util.Map<String, Object> input) {
                // Visibility hook — no action required here.
            }

            @Override
            public void onToolPermissionNeeded(String callId, String name,
                    java.util.Map<String, Object> input, Consumer<PermissionDecision> responder) {
                // Auto-approve all tools in headless/CI mode.
                responder.accept(PermissionDecision.APPROVE_ONCE);
            }

            @Override
            public void onToolResult(String callId, String name, ToolResult result) {
                // Visibility hook — the ReActAgent has already appended the result to history.
            }

            @Override
            public void onUsage(int inputTokens, int outputTokens) {
                if (activePlan != null) {
                    long latency = System.currentTimeMillis() - inferenceStartTime;
                    ModelRoutingTelemetry.getInstance().recordSuccess(
                            activePlan.selectedModel(),
                            latency,
                            (long) inputTokens + outputTokens
                    );
                }
            }

            @Override
            public void onDone(String stopReason) {
                AgentResponse response = AgentResponse.builder()
                    .id(id)
                    .success(true)
                    .content(contentBuffer.toString())
                    .metadata("stopReason", stopReason)
                    .build();

                stateStore.complete(id, response);
                AgentExecutionState outcome = stateStore.get(id);
                DefaultAgentExecution.this.status = outcome.status();
                if (outcome.status() == ExecutionStatus.CANCELLED) {
                    completeCancelled(future);
                    return;
                }
                if (outcome.status() != ExecutionStatus.COMPLETED) {
                    return;
                }

                if (stateStore != null) {
                    stateStore.checkpoint(id, agentContext);
                }
                if (checkpointStore != null) {
                    checkpointStore.save(id, agentContext);
                }

                // Collect cache entry IDs for this execution (traceability).
                java.util.List<String> cacheEntryIds = java.util.List.of();
                if (executionCache != null) {
                    cacheEntryIds = executionCache.listByExecution(id)
                            .stream()
                            .map(ExecutionCacheEntry::cacheId)
                            .collect(java.util.stream.Collectors.toList());
                }
                response = AgentResponse.builder()
                    .id(id)
                    .success(true)
                    .content(contentBuffer.toString())
                    .metadata("stopReason", stopReason)
                    .metadata("cacheEntryCount", String.valueOf(cacheEntryIds.size()))
                    .build();

                if (memoryManager != null && request != null && request.content() != null) {
                    memoryManager.storeInteraction(request.content(), response.content());
                }
                future.complete(response);
            }

            @Override
            public void onError(String message) {
                stateStore.fail(id, new RuntimeException(message));
                AgentExecutionState outcome = stateStore.get(id);
                DefaultAgentExecution.this.status = outcome.status();
                if (outcome.status() == ExecutionStatus.CANCELLED) {
                    completeCancelled(future);
                    return;
                }
                if (outcome.status() != ExecutionStatus.FAILED
                        && outcome.status() != ExecutionStatus.ERROR
                        && outcome.status() != ExecutionStatus.TIMEOUT) {
                    return;
                }

                AgentResponse response = AgentResponse.builder()
                    .id(id)
                    .success(false)
                    .error(message)
                    .build();
                future.complete(response);
            }
        });
    }

    /** Returns a completed stub response — used when a real execution cannot be performed. */
    private void stubComplete(CompletableFuture<AgentResponse> future, String reason) {
        AgentResponse response = AgentResponse.builder()
            .id(id)
            .success(true)
            .content(reason)
            .build();
        stateStore.complete(id, response);
        AgentExecutionState outcome = stateStore.get(id);
        this.status = outcome.status();
        if (outcome.status() == ExecutionStatus.CANCELLED) {
            completeCancelled(future);
            return;
        }
        if (outcome.status() != ExecutionStatus.COMPLETED) {
            return;
        }
        stateStore.checkpoint(id, agentContext);
        if (checkpointStore != null) {
            checkpointStore.save(id, agentContext);
        }
        future.complete(response);
    }

    private void checkpoint(String executionId, AgentContext snapshot) {
        if (stateStore == null || snapshot == null) {
            return;
        }
        java.util.Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("tenantId", toolExecutionContext.tenantId());
        metadata.put("userId", toolExecutionContext.userId());
        metadata.put("agentId", toolExecutionContext.agentId());
        metadata.put("principalType", toolExecutionContext.principalType().name());
        metadata.put("principalId", toolExecutionContext.principalId());
        stateStore.checkpoint(executionId, snapshot, metadata);
    }

    private void throwIfCancellationRequested() {
        if (cancellationToken.isCancellationRequested()) {
            throw new CancellationException("Execution cancelled: " + id);
        }
    }

    private void signalCancellation() {
        cancellationToken.cancel();
        CompletableFuture<AgentResponse> future = activeFuture;
        if (future != null) {
            completeCancelled(future);
        }
    }

    private void completeCancelled(CompletableFuture<AgentResponse> future) {
        future.completeExceptionally(new CancellationException("Execution cancelled: " + id));
    }

    // -------------------------------------------------------------------------
    // Synchronous convenience
    // -------------------------------------------------------------------------

    @Override
    public AgentResponse executeSync() {
        try {
            return execute().toCompletableFuture().join();
        } catch (Exception e) {
            this.status = ExecutionStatus.FAILED;
            return AgentResponse.builder()
                .id(id)
                .success(false)
                .error(e.getMessage())
                .build();
        }
    }

    @Override
    public AgentResponse join() {
        if (status == ExecutionStatus.COMPLETED
                || status == ExecutionStatus.FAILED
                || status == ExecutionStatus.CANCELLED) {
            return AgentResponse.builder()
                .id(id)
                .success(status == ExecutionStatus.COMPLETED)
                .build();
        }
        return executeSync();
    }

    // -------------------------------------------------------------------------
    // Lifecycle controls
    // -------------------------------------------------------------------------

    @Override
    public void pause() {
        if (this.status != ExecutionStatus.RUNNING) {
            return;
        }

        if (stateStore != null) {
            stateStore.checkpoint(id, agentContext);
        }

        if (checkpointStore != null) {
            checkpointStore.save(id, agentContext);
        }

        stateStore.transitionStatus(id, ExecutionStatus.RUNNING, ExecutionStatus.PAUSED);
        this.status = ExecutionStatus.PAUSED;
    }

    @Override
    public void resume() {
        if (this.status != ExecutionStatus.PAUSED) {
            return;
        }

        stateStore.transitionStatus(id, ExecutionStatus.PAUSED, ExecutionStatus.RUNNING);
        this.status = ExecutionStatus.RUNNING;
    }

    @Override
    public void cancel() {
        ExecutionStatus current = stateStore.get(id).status();
        this.status = current;
        if (current == ExecutionStatus.COMPLETED) {
            return;
        }
        if (current == ExecutionStatus.CANCELLED) {
            signalCancellation();
            return;
        }

        try {
            stateStore.transitionStatus(id, current, ExecutionStatus.CANCELLED);
            this.status = ExecutionStatus.CANCELLED;
            signalCancellation();
        } catch (InvalidExecutionTransitionException race) {
            // A completion/failure callback may have won the state-store CAS.
            // Reconcile the local view rather than reporting a stale cancellation.
            ExecutionStatus actual = stateStore.get(id).status();
            this.status = actual;
            if (actual == ExecutionStatus.CANCELLED) {
                signalCancellation();
                return;
            }
            if (actual != ExecutionStatus.COMPLETED
                    && actual != ExecutionStatus.FAILED && actual != ExecutionStatus.ERROR
                    && actual != ExecutionStatus.TIMEOUT) {
                throw race;
            }
        }
    }

    private static AgentToolExecutionContext defaultToolExecutionContext(
            String executionId, AgentDefinition agent, String tenantId, String userId, ExecutionBudget budget) {
        String agentId = agent != null ? agent.id().value().asString() : "unknown-agent";
        PrincipalType principalType = userId != null && !userId.isBlank()
                ? PrincipalType.USER : PrincipalType.ANONYMOUS;
        return new AgentToolExecutionContext(
                executionId, executionId, null, tenantId, userId, agentId,
                principalType, principalType == PrincipalType.USER ? userId : null,
                java.util.Set.of(), budget, ToolBudget.unlimited(), java.util.Map.of(),
                java.util.Map.of(), java.time.Instant.now());
    }

    private InferencePolicy mapBudgetToPolicy(ExecutionBudget budget) {
        if (budget == null) {
            return InferencePolicy.defaults();
        }
        if (budget.contextTokens() >= 100_000 || (budget.maxDuration() != null && budget.maxDuration().toMinutes() >= 15)) {
            return InferencePolicy.thorough();
        }
        if (budget.maxSteps() <= 15 || (budget.maxDuration() != null && budget.maxDuration().toMinutes() <= 2)) {
            return InferencePolicy.fast();
        }
        if (!budget.isCachingEnabled()) {
            return InferencePolicy.debug();
        }
        return InferencePolicy.balanced();
    }
}
