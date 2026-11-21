package tech.kayys.andalus.execution;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import tech.kayys.andalus.execution.AgentToolExecutionContext;
import tech.kayys.andalus.execution.cache.CacheNamespace;
import tech.kayys.andalus.execution.cache.ExecutionCache;
import tech.kayys.andalus.execution.cache.ExecutionCacheEntry;
import tech.kayys.andalus.execution.event.EventLedger;
import tech.kayys.andalus.execution.event.ExecutionEvent;
import tech.kayys.andalus.execution.event.ExecutionEventType;
import tech.kayys.andalus.execution.event.ToolExecution;
import tech.kayys.andalus.execution.event.ToolExecutionLedger;
import tech.kayys.andalus.execution.governance.PolicyDecision;
import tech.kayys.andalus.execution.governance.ToolBudget;
import tech.kayys.andalus.execution.governance.ToolCapabilityLevel;
import tech.kayys.andalus.execution.governance.ToolPermissionContext;
import tech.kayys.andalus.execution.governance.PolicyEvaluationContexts;
import tech.kayys.andalus.execution.governance.ToolPolicyEvaluator;
import tech.kayys.andalus.resilience.CircuitBreakerRegistry;
import tech.kayys.andalus.resilience.DefaultRetryPolicy;
import tech.kayys.andalus.resilience.Retry;
import tech.kayys.andalus.resilience.RetryPolicy;
import tech.kayys.andalus.tool.Tool;
import tech.kayys.andalus.tool.ToolInvocation;
import tech.kayys.andalus.tool.ToolResult;
import tech.kayys.andalus.tool.ToolContext;
import tech.kayys.andalus.tool.SimpleToolContext;

/**
 * Default implementation of {@link AgentToolExecutor}.
 *
 * <p>Implements a real tool execution pipeline:
 * <ol>
 *   <li>Capability / authorization check (tool existence)</li>
 *   <li>Schema validation (required fields in arguments)</li>
 *   <li>Approval policy (writes require HITL in production)</li>
 *   <li>Guardrail check ({@link ToolGuardrailCheck} — optional CDI bean)</li>
 *   <li>Circuit breaker protection</li>
 *   <li>Retry with exponential back-off</li>
 *   <li>Timeout enforcement (30s hard cap per tool call)</li>
 *   <li>{@link Tool#execute(ToolInvocation, ToolContext, tech.kayys.andalus.tool.CancellationToken)} — the actual execution</li>
 * </ol>
 */
@ApplicationScoped
public class DefaultAgentToolExecutor implements AgentToolExecutor, AgentToolExecutor.ToolAware {

    private static final Logger LOG = Logger.getLogger(DefaultAgentToolExecutor.class.getName());

    /** Tools wired via CDI — each Tool bean registers itself automatically. */
    @Inject
    Instance<Tool> toolInstances;

    /**
     * Optional guardrail check — provided by {@code andalus-guardrails-runtime} when deployed.
     * If the bean is not present, the guardrail step is silently skipped.
     */
    @Inject
    Instance<ToolGuardrailCheck> guardrailCheckInstances;

    /**
     * Optional execution cache — provided by {@code andalus-runtime-core} when deployed.
     * If the bean is not present, caching is silently skipped.
     */
    @Inject
    Instance<ExecutionCache> executionCacheInstances;

    /**
     * One circuit breaker per tool name — keeps failures isolated.
     * Plain field rather than @Inject so the module stays self-contained.
     */
    private final CircuitBreakerRegistry circuitBreakerRegistry = new CircuitBreakerRegistry();

    /** 3 attempts, 500ms initial delay, 2× back-off, 10s max wait between retries. */
    private static final RetryPolicy RETRY_POLICY =
        new DefaultRetryPolicy(3, 500, 10_000, 2.0, java.util.List.of(Exception.class));

    /** Hard timeout for a single tool execution. */
    private static final long TOOL_TIMEOUT_SECONDS = 30;

    /**
     * Phase 6 — Tool Governance
     */
    @Inject
    Instance<ToolPolicyEvaluator> policyEvaluatorInstances;

    @Inject
    Instance<EventLedger> eventLedgerInstances;
    
    @Inject
    Instance<ToolExecutionLedger> toolExecutionLedgerInstances;

    /** Monotonic audit sequence per executor instance. */
    private final java.util.concurrent.atomic.AtomicLong auditSeq = new java.util.concurrent.atomic.AtomicLong();

    // -------------------------------------------------------------------------
    // AgentToolExecutor.ToolAware
    // -------------------------------------------------------------------------

    @Override
    public java.util.List<Tool> availableTools() {
        java.util.List<Tool> tools = new java.util.ArrayList<>();
        if (toolInstances != null) {
            toolInstances.forEach(tools::add);
        }
        return tools;
    }

    // -------------------------------------------------------------------------
    // AgentToolExecutor
    // -------------------------------------------------------------------------

    @Override
    public CompletionStage<AgentDecision> execute(ToolInvocation invocation) {
        AgentToolExecutionContext legacyContext = new AgentToolExecutionContext(
                "legacy-" + java.util.UUID.randomUUID(), null, null, null, null,
                "legacy-agent", AgentToolExecutionContext.PrincipalType.ANONYMOUS, null,
                java.util.Set.of(), ExecutionBudget.balanced(), ToolBudget.unlimited(),
                Map.of(), Map.of(), Instant.now());
        return execute(invocation, legacyContext, null);
    }

    @Override
    public CompletionStage<AgentDecision> execute(
            ToolInvocation invocation, AgentToolExecutionContext context) {
        return execute(invocation, context, null);
    }

    @Override
    public CompletionStage<AgentDecision> execute(
            ToolInvocation invocation,
            AgentToolExecutionContext context,
            tech.kayys.andalus.tool.CancellationToken cancellation) {
        if (invocation == null) {
            return CompletableFuture.completedFuture(AgentDecision.fail("Tool invocation is required."));
        }
        if (context == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("AgentToolExecutionContext is required."));
        }
        if (cancellation != null && cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException(
                    "Execution cancelled before tool dispatch."));
        }

        // Capture the immutable context in this invocation; never read executor-wide identity.
        final AgentToolExecutionContext execution = context;
        final String toolName = invocation.name();
        final ExecutionBudget executionBudget = execution.executionBudget() != null
                ? execution.executionBudget() : ExecutionBudget.balanced();
        final ToolBudget budget = execution.toolBudget() != null
                ? execution.toolBudget() : ToolBudget.unlimited();

        // --- 1. Capability / existence check ---
        Tool tool = resolveTool(toolName);
        if (tool == null) {
            LOG.warning(() -> "Tool not found: " + toolName);
            return CompletableFuture.completedFuture(AgentDecision.fail("Tool not found: " + toolName));
        }

        // --- 2. Schema validation ---
        String validationError = validateArguments(tool, invocation.arguments());
        if (validationError != null) {
            LOG.warning(() -> "Schema validation failed for tool " + toolName + ": " + validationError);
            return CompletableFuture.completedFuture(
                    AgentDecision.fail("Schema validation failed: " + validationError));
        }

        // --- 3. Policy evaluation with complete identity; no wildcard role ---
        ToolPermissionContext permission = new ToolPermissionContext(
                execution.tenantId(), execution.userId(), execution.executionId(),
                java.util.List.copyOf(execution.roles()), toolName, resolveCapabilityLevel(tool));
        var policyContext = PolicyEvaluationContexts.builder(permission, invocation)
                .tenantId(execution.tenantId())
                .userId(execution.userId())
                .agentId(execution.agentId())
                .roles(execution.roles())
                .executionId(execution.executionId())
                .correlationId(execution.correlationId())
                .parentExecutionId(execution.parentExecutionId())
                .attributes(execution.attributes())
                .build();
        PolicyDecision policyDecision = evaluatePolicy(policyContext);

        switch (policyDecision) {
            case PolicyDecision.Deny d -> {
                LOG.info(() -> "Tool " + toolName + " denied by policy [" + d.policyId() + "]: " + d.reason());
                emitAudit(execution, ExecutionEventType.TOOL_FAILED, toolName,
                        Map.of("reason", "policy deny", "policy", d.policyId(), "detail", d.reason()));
                return CompletableFuture.completedFuture(
                        AgentDecision.fail("Policy denied tool " + toolName + ": " + d.reason()));
            }
            case PolicyDecision.RequireApproval ra -> {
                LOG.info(() -> "Tool " + toolName + " requires approval: " + ra.reason());
                emitAudit(execution, ExecutionEventType.TOOL_APPROVAL_REQUIRED, toolName,
                        Map.of("policy", ra.policyId(), "reason", ra.reason()));
                return CompletableFuture.completedFuture(new AgentDecision.WaitForApproval(invocation));
            }
            default -> { }
        }

        // --- 4. Guardrail check ---
        ToolGuardrailCheck guardrailCheck = resolveGuardrailCheck();
        if (guardrailCheck != null) {
            ToolGuardrailCheck.Result guardResult = guardrailCheck.check(toolName, invocation.arguments());
            if (!guardResult.allowed()) {
                LOG.warning(() -> "Guardrail blocked tool " + toolName + ": " + guardResult.reason()
                        + " (policies: " + guardResult.triggeredPolicies() + ")");
                return CompletableFuture.completedFuture(AgentDecision.fail("Guardrail blocked: " + guardResult.reason()));
            }
        }

        // Reserve the invocation atomically only after authorization and guardrails
        // have passed. Cached invocations count as tool calls too.
        if (!budget.tryAcquireCall()) {
            emitAudit(execution, ExecutionEventType.TOOL_FAILED, toolName,
                    Map.of("reason", "budget exhausted", "budget", budget.toString()));
            return CompletableFuture.completedFuture(
                    AgentDecision.fail("Tool call budget exhausted for " + budget));
        }

        // Cache lookup happens only after policy and guardrail evaluation so a hit
        // cannot bypass current authorization decisions.
        ExecutionCache cache = resolveExecutionCache();
        if (cache != null && executionBudget.toolCacheEnabled() && isCacheable(tool)) {
            String inputHash = ExecutionCache.hashTool(toolName, invocation.arguments());
            Optional<ExecutionCacheEntry> hit = cache.lookup(
                    CacheNamespace.TOOL, execution.tenantId(), execution.userId(), inputHash);
            if (hit.isPresent()) {
                ExecutionCacheEntry entry = hit.get();
                LOG.fine(() -> "Cache HIT for tool " + toolName + " (" + inputHash + ")");
                if (entry.value() instanceof ToolResult cachedResult) {
                    budget.recordUsage(0, 0.0);
                    recordToolExecution(execution, toolName, invocation.arguments(), 0, true);
                    return CompletableFuture.completedFuture(
                            new AgentDecision.ToolCompleted(invocation, cachedResult));
                }
            }
        }

        // --- 5–8. Circuit breaker + retry + timeout + execution; 9. cache store ---
        return CompletableFuture.supplyAsync(() -> {
            if (cancellation != null && cancellation.isCancellationRequested()) {
                throw new java.util.concurrent.CancellationException("Execution cancelled before tool execution.");
            }
            long startMs = System.currentTimeMillis();
            AgentDecision decision = runWithResilience(tool, invocation, execution, cancellation);
            long durationMs = System.currentTimeMillis() - startMs;
            budget.recordUsage(durationMs, 0.0);

            if (decision instanceof AgentDecision.ToolCompleted tc) {
                emitAudit(execution, ExecutionEventType.TOOL_EXECUTED, toolName,
                        Map.of("durationMs", durationMs));
                recordToolExecution(execution, toolName, invocation.arguments(), durationMs, false);
            } else if (decision instanceof AgentDecision.Fail fail) {
                emitAudit(execution, ExecutionEventType.TOOL_FAILED, toolName,
                        Map.of("durationMs", durationMs, "error", fail.error()));
            }

            if (cache != null && executionBudget.toolCacheEnabled() && isCacheable(tool)
                    && decision instanceof AgentDecision.ToolCompleted completed) {
                String inputHash = ExecutionCache.hashTool(toolName, invocation.arguments());
                Instant expires = ExecutionCache.expiresAt(executionBudget.toolCacheTtl());
                String toolId = tool.id() != null ? tool.id().toString() : toolName;
                cache.store(ExecutionCacheEntry.builder()
                        .namespace(CacheNamespace.TOOL)
                        .tenantId(execution.tenantId())
                        .userId(execution.userId())
                        .executionId(execution.executionId())
                        .toolId(toolId)
                        .inputHash(inputHash)
                        .value(completed.result())
                        .expiresAt(expires)
                        .provenance(toolName + "(" + invocation.arguments() + ")")
                        .build());
                LOG.fine(() -> "Cache STORE for tool " + toolName + " (" + inputHash + ")");
            }
            return decision;
        });
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private Tool resolveTool(String name) {
        if (toolInstances == null) return null;
        for (Tool t : toolInstances) {
            if (name.equals(t.descriptor().name())) {
                return t;
            }
        }
        return null;
    }

    private String validateArguments(Tool tool, Map<String, Object> arguments) {
        if (tool.descriptor() == null) return null;
        tech.kayys.andalus.tool.ToolDescriptor descriptor = tool.descriptor();
        // If the descriptor carries required field names, verify them.
        if (descriptor.inputSchema() != null) {
            // inputSchema is a JSON object string — do a lightweight required-field check.
            String schema = descriptor.inputSchema().toString();
            if (schema.contains("\"required\"")) {
                // Extract required fields via simple string scan (avoids pulling in a JSON lib).
                int reqIdx = schema.indexOf("\"required\"");
                int arrStart = schema.indexOf('[', reqIdx);
                int arrEnd = schema.indexOf(']', arrStart);
                if (arrStart >= 0 && arrEnd > arrStart) {
                    String reqSection = schema.substring(arrStart + 1, arrEnd);
                    for (String tok : reqSection.split(",")) {
                        String field = tok.replaceAll("[\"\\[\\]\\s]", "").trim();
                        if (!field.isEmpty() && (arguments == null || !arguments.containsKey(field))) {
                            return "missing required field: " + field;
                        }
                    }
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Phase 6: Governance helpers
    // -------------------------------------------------------------------------

    private PolicyDecision evaluatePolicy(tech.kayys.andalus.execution.governance.PolicyEvaluationContext context) {
        if (policyEvaluatorInstances == null || policyEvaluatorInstances.isUnsatisfied()) {
            return PolicyDecision.deny(
                    "No ToolPolicyEvaluator is configured; tool execution is denied by default.",
                    "runtime-policy-evaluator-required");
        }
        return policyEvaluatorInstances.get().evaluate(context);
    }

    private void emitAudit(AgentToolExecutionContext context, ExecutionEventType type,
                          String toolName, Map<String, Object> payload) {
        if (eventLedgerInstances == null || eventLedgerInstances.isUnsatisfied()) return;
        EventLedger ledger = eventLedgerInstances.get();
        ledger.record(ExecutionEvent.of(
            context.executionId(), auditSeq.getAndIncrement(), type, toolName, payload));
    }

    private void recordToolExecution(AgentToolExecutionContext context, String toolName,
                                     Map<String, Object> arguments, long durationMs, boolean cacheHit) {
        if (toolExecutionLedgerInstances == null || toolExecutionLedgerInstances.isUnsatisfied()) return;
        ToolExecutionLedger ledger = toolExecutionLedgerInstances.get();
        ledger.record(new ToolExecution(
            context.executionId(), context.tenantId(), context.userId(), toolName,
            ExecutionCache.hashInputs(arguments), durationMs, cacheHit, Instant.now()));
    }

    /**
     * Resolves governance from trusted registration metadata, never from the
     * model-controlled invocation name. Missing or CUSTOM metadata fails closed.
     */
    static ToolCapabilityLevel resolveCapabilityLevel(Tool tool) {
        if (tool == null || tool.descriptor() == null) {
            return ToolCapabilityLevel.SYSTEM;
        }
        var profile = tool.descriptor().executionProfile();
        if (profile == null || profile.kind() == null) {
            return ToolCapabilityLevel.SYSTEM;
        }
        return switch (profile.kind()) {
            case READ -> profile.readOnly()
                    ? ToolCapabilityLevel.READ : ToolCapabilityLevel.WRITE;
            case WRITE -> ToolCapabilityLevel.WRITE;
            case SHELL -> ToolCapabilityLevel.SHELL;
            case NETWORK -> ToolCapabilityLevel.NETWORK;
            case SYSTEM -> ToolCapabilityLevel.SYSTEM;
            case CUSTOM -> ToolCapabilityLevel.SYSTEM;
        };
    }

    private AgentDecision runWithResilience(
            Tool tool, ToolInvocation invocation, AgentToolExecutionContext execution,
            tech.kayys.andalus.tool.CancellationToken cancellation) {
        String toolName = invocation.name();
        var cb = circuitBreakerRegistry.getOrCreate(toolName);

        try {
            ToolResult result = cb.execute(() ->
                Retry.retry(() -> executeWithTimeout(tool, invocation, execution, cancellation), RETRY_POLICY)
            );
            LOG.fine(() -> "Tool " + toolName + " succeeded: success=" + result.isSuccess());
            return new AgentDecision.ToolCompleted(invocation, result);

        } catch (tech.kayys.andalus.resilience.CircuitBreakerOpenException cbOpen) {
            LOG.warning(() -> "Circuit breaker OPEN for tool " + toolName);
            return AgentDecision.fail("Tool " + toolName + " circuit breaker is open — too many failures.");

        } catch (TimeoutException te) {
            LOG.warning(() -> "Tool " + toolName + " timed out after " + TOOL_TIMEOUT_SECONDS + "s.");
            return AgentDecision.fail("Tool " + toolName + " timed out.");

        } catch (Exception e) {
            LOG.log(Level.WARNING, "Tool " + toolName + " failed after retries", e);
            return AgentDecision.fail("Tool " + toolName + " failed: " + e.getMessage());
        }
    }

    /** Only explicitly deterministic tools may use the result cache. */
    static boolean isCacheable(Tool tool) {
        if (tool == null || tool.descriptor() == null) return false;
        var profile = tool.descriptor().executionProfile();
        return profile != null && profile.deterministic();
    }

    /**
     * Builds the immutable tool-facing context from server-created execution data.
     * Agent constraints provide deployment/agent configuration; execution attributes
     * may add trusted per-run values. Identity fields are written last and cannot be
     * shadowed by either map.
     */
    static ToolContext toolContextFor(AgentToolExecutionContext execution) {
        java.util.Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.putAll(execution.constraints());
        attributes.putAll(execution.attributes());
        attributes.put("andalus.execution.id", execution.executionId());
        attributes.put("andalus.agent.id", execution.agentId());
        if (execution.tenantId() != null) attributes.put("andalus.tenant.id", execution.tenantId());
        if (execution.userId() != null) attributes.put("andalus.user.id", execution.userId());
        attributes.put("andalus.principal.type", execution.principalType().name());
        attributes.put("andalus.roles", execution.roles());
        return SimpleToolContext.of(attributes);
    }

    private ToolResult executeWithTimeout(
            Tool tool, ToolInvocation invocation, AgentToolExecutionContext execution,
            tech.kayys.andalus.tool.CancellationToken cancellation) throws Exception {
        if (cancellation != null && cancellation.isCancellationRequested()) {
            throw new java.util.concurrent.CancellationException("Tool execution cancelled before dispatch.");
        }
        var future = tool.execute(invocation, toolContextFor(execution), cancellation);
        try {
            return future.get(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException ee) {
            throw (Exception) ee.getCause();
        }
    }

    /**
     * Returns the first available {@link ToolGuardrailCheck} bean, or {@code null}
     * when {@code andalus-guardrails-runtime} is not deployed.
     */
    private ToolGuardrailCheck resolveGuardrailCheck() {
        if (guardrailCheckInstances == null || guardrailCheckInstances.isUnsatisfied()) {
            return null;
        }
        return guardrailCheckInstances.get();
    }

    /**
     * Returns the first available {@link ExecutionCache} bean, or {@code null}
     * when the cache module is not deployed or CDI is not active.
     */
    private ExecutionCache resolveExecutionCache() {
        if (executionCacheInstances == null || executionCacheInstances.isUnsatisfied()) {
            return null;
        }
        return executionCacheInstances.get();
    }
}
