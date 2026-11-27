package tech.kayys.andalus.agent.core.core;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import tech.kayys.andalus.agent.spi.AgentRequest;
import tech.kayys.andalus.agent.spi.AgentResponse;
import tech.kayys.andalus.execution.AgentCallerContext;
import tech.kayys.andalus.execution.AgentExecution;
import tech.kayys.andalus.execution.AgentExecutionService;
import tech.kayys.andalus.execution.ExecutionBudget;

/**
 * Bridges the transport request contract to the durable runtime execution
 * service. Agent selection is explicit and delegated to a tenant-aware
 * {@link AgentDefinitionResolver}; this class never constructs an ad-hoc
 * agent definition from request text.
 */
@ApplicationScoped
public class RuntimeAgentClient implements AgentClient {

    @Inject
    AgentExecutionService executions;

    @Inject
    Instance<AgentDefinitionResolver> definitionResolvers;

    @Override
    public Uni<AgentResponse> execute(AgentRequest request) {
        return Uni.createFrom().failure(new UnsupportedOperationException(
                "Runtime execution requires a trusted caller context and explicit agentId"));
    }

    @Override
    public Uni<AgentResponse> execute(AgentRequest request, AgentCallerContext caller) {
        return Uni.createFrom().failure(new IllegalArgumentException(
                "agentId is required to resolve an executable agent definition"));
    }

    @Override
    public Uni<AgentResponse> execute(AgentRequest request, AgentCallerContext caller, String agentId) {
        if (request == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("request is required"));
        }
        if (caller == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("trusted caller context is required"));
        }
        if (agentId == null || agentId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("agentId is required"));
        }
        if (executions == null) {
            return Uni.createFrom().failure(new IllegalStateException("AgentExecutionService is not configured"));
        }
        if (definitionResolvers == null || definitionResolvers.isUnsatisfied()) {
            return Uni.createFrom().failure(new IllegalStateException(
                    "No AgentDefinitionResolver is configured; refusing to select an implicit agent"));
        }

        try {
            Optional<tech.kayys.andalus.core.AgentDefinition> resolved =
                    definitionResolvers.get().resolve(agentId, request, caller);
            if (resolved == null || resolved.isEmpty()) {
                return Uni.createFrom().failure(new NotFoundException(
                        "Agent not found or caller is not authorized"));
            }

            tech.kayys.andalus.agent.AgentRequest runtimeRequest = toRuntimeRequest(request, caller);
            AgentExecution execution = executions.create(
                    resolved.get(), runtimeRequest, executionBudget(request), caller);
            CompletionStage<tech.kayys.andalus.agent.AgentResponse> completion = execution.execute();

            return Uni.createFrom().completionStage(completion)
                    .onCancellation().invoke(execution::cancel)
                    .map(response -> toTransportResponse(request, execution.id(), response));
        } catch (RuntimeException error) {
            return Uni.createFrom().failure(error);
        }
    }

    /**
     * Caller-provided limits may tighten the balanced profile but cannot raise
     * the deployment's default step or duration ceilings.
     */
    private ExecutionBudget executionBudget(AgentRequest request) {
        ExecutionBudget base = ExecutionBudget.balanced();
        Duration requestedDuration = request.timeout();
        Duration maxDuration = requestedDuration != null && !requestedDuration.isNegative()
                && !requestedDuration.isZero() && requestedDuration.compareTo(base.maxDuration()) < 0
                        ? requestedDuration : base.maxDuration();
        int maxSteps = request.maxSteps() > 0
                ? Math.min(request.maxSteps(), base.maxSteps()) : base.maxSteps();
        return new ExecutionBudget(
                maxDuration,
                maxSteps,
                base.maxToolCalls(),
                base.maxInputTokens(),
                base.maxOutputTokens(),
                base.contextTokens(),
                base.maxRetries(),
                base.maxConcurrentTools(),
                base.toolCacheEnabled(),
                base.toolCacheTtl(),
                base.retrievalCacheEnabled(),
                base.retrievalCacheTtl(),
                base.researchCacheEnabled(),
                base.researchCacheTtl(),
                base.maxA2ARequests(),
                base.maxResearchSources());
    }

    private tech.kayys.andalus.agent.AgentRequest toRuntimeRequest(
            AgentRequest request, AgentCallerContext caller) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("strategy", request.strategy() != null ? request.strategy().name() : "REACT");
        metadata.put("systemPrompt", request.systemPrompt());
        metadata.put("modelId", request.modelId());
        metadata.put("maxSteps", request.maxSteps());
        metadata.put("timeout", request.timeout() != null ? request.timeout().toString() : null);
        metadata.put("streamRequested", request.stream());

        return tech.kayys.andalus.agent.AgentRequest.builder()
                .sessionId(request.sessionId())
                .tenantId(caller.tenantId())
                .userId(caller.userId())
                .content(request.prompt())
                .metadata(metadata)
                .parameters(request.context())
                .build();
    }

    private AgentResponse toTransportResponse(
            AgentRequest request,
            String executionId,
            tech.kayys.andalus.agent.AgentResponse response) {
        if (response == null) {
            return AgentResponse.builder()
                    .runId(executionId)
                    .requestId(request.requestId())
                    .successful(false)
                    .error("Runtime execution returned no response")
                    .strategy(request.strategy() != null ? request.strategy().name() : null)
                    .build();
        }

        int totalSteps = 0;
        Object steps = response.metadata() != null ? response.metadata().get("totalSteps") : null;
        if (steps instanceof Number number) {
            totalSteps = Math.max(0, number.intValue());
        }

        return AgentResponse.builder()
                .runId(executionId)
                .requestId(request.requestId())
                .answer(response.content())
                .totalSteps(totalSteps)
                .successful(response.success())
                .error(response.error())
                .strategy(request.strategy() != null ? request.strategy().name() : null)
                .durationMs(response.responseTimeMs())
                .build();
    }
}
