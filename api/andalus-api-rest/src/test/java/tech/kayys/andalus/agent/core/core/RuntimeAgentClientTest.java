package tech.kayys.andalus.agent.core.core;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.NotFoundException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.agent.spi.AgentRequest;
import tech.kayys.andalus.agent.spi.AgentResponse;
import tech.kayys.andalus.agent.spi.OrchestrationStrategy;
import tech.kayys.andalus.core.AgentDefinition;
import tech.kayys.andalus.execution.AgentCallerContext;
import tech.kayys.andalus.execution.AgentExecution;
import tech.kayys.andalus.execution.AgentExecutionService;
import tech.kayys.andalus.execution.ExecutionBudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeAgentClientTest {

    @Test
    void resolvesAgentForCallerAndPassesTrustedContextIntoExecutionService() {
        AtomicReference<String> resolvedAgentId = new AtomicReference<>();
        AtomicReference<AgentCallerContext> resolvedCaller = new AtomicReference<>();
        AgentDefinition definition = AgentDefinition.builder().build();
        AgentDefinitionResolver resolver = (agentId, caller) -> {
            resolvedAgentId.set(agentId);
            resolvedCaller.set(caller);
            return Optional.of(definition);
        };

        AtomicReference<AgentCallerContext> executionCaller = new AtomicReference<>();
        AtomicReference<tech.kayys.andalus.agent.AgentRequest> runtimeRequest = new AtomicReference<>();
        AtomicReference<ExecutionBudget> runtimeBudget = new AtomicReference<>();
        AgentExecutionService service = new AgentExecutionService() {
            @Override
            public AgentExecution create(AgentDefinition agent,
                    tech.kayys.andalus.agent.AgentRequest request,
                    ExecutionBudget budget,
                    AgentCallerContext caller) {
                assertThat(agent).isSameAs(definition);
                executionCaller.set(caller);
                runtimeRequest.set(request);
                runtimeBudget.set(budget);
                return execution("execution-123");
            }
        };

        RuntimeAgentClient client = new RuntimeAgentClient();
        client.executions = service;
        client.definitionResolvers = resolverInstance(resolver);

        AgentCallerContext caller = AgentCallerContext.user("tenant-a", "user-a",
                Set.of("agent:run"), Set.of("tool:read"));
        AgentRequest request = AgentRequest.builder()
                .prompt("summarize this")
                .strategy(OrchestrationStrategy.REACT)
                .tenantId("forged-tenant")
                .userId("forged-user")
                .sessionId("session-a")
                .modelId("model-a")
                .maxSteps(7)
                .timeout(Duration.ofSeconds(30))
                .context(Map.of("traceId", "trace-a"))
                .build();

        AgentResponse response = client.execute(request, caller, "agent-a").await().indefinitely();

        assertThat(resolvedAgentId.get()).isEqualTo("agent-a");
        assertThat(resolvedCaller.get()).isSameAs(caller);
        assertThat(executionCaller.get()).isSameAs(caller);
        assertThat(runtimeRequest.get().tenantId()).isEqualTo("tenant-a");
        assertThat(runtimeRequest.get().userId()).isEqualTo("user-a");
        assertThat(runtimeRequest.get().sessionId()).isEqualTo("session-a");
        assertThat(runtimeRequest.get().content()).isEqualTo("summarize this");
        assertThat(runtimeRequest.get().parameters()).containsEntry("traceId", "trace-a");
        assertThat(runtimeBudget.get().maxSteps()).isEqualTo(7);
        assertThat(runtimeBudget.get().maxDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(runtimeRequest.get().metadata())
                .containsEntry("strategy", "REACT")
                .containsEntry("modelId", "model-a")
                .containsEntry("maxSteps", 7);

        assertThat(response.runId()).isEqualTo("execution-123");
        assertThat(response.requestId()).isEqualTo(request.requestId());
        assertThat(response.answer()).isEqualTo("runtime answer");
        assertThat(response.successful()).isTrue();
    }

    @Test
    void failsClosedWhenNoTenantAwareResolverIsConfigured() {
        RuntimeAgentClient client = new RuntimeAgentClient();
        client.executions = new AgentExecutionService() { };
        client.definitionResolvers = resolverInstance(null);

        assertThatThrownBy(() -> client.execute(
                AgentRequest.builder().prompt("hello").build(),
                AgentCallerContext.user("tenant-a", "user-a", Set.of(), Set.of()),
                "agent-a").await().indefinitely())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No AgentDefinitionResolver");
    }

    @Test
    void hidesMissingOrUnauthorizedAgentAsNotFound() {
        RuntimeAgentClient client = new RuntimeAgentClient();
        client.executions = new AgentExecutionService() { };
        client.definitionResolvers = resolverInstance((agentId, caller) -> Optional.empty());

        assertThatThrownBy(() -> client.execute(
                AgentRequest.builder().prompt("hello").build(),
                AgentCallerContext.user("tenant-a", "user-a", Set.of(), Set.of()),
                "private-agent").await().indefinitely())
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Agent not found or caller is not authorized");
    }

    @Test
    void requiresExplicitAgentId() {
        RuntimeAgentClient client = new RuntimeAgentClient();
        client.executions = new AgentExecutionService() { };
        client.definitionResolvers = resolverInstance((agentId, caller) -> Optional.empty());

        assertThatThrownBy(() -> client.execute(
                AgentRequest.builder().prompt("hello").build(),
                AgentCallerContext.user("tenant-a", "user-a", Set.of(), Set.of()),
                " ").await().indefinitely())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("agentId is required");
    }

    private AgentExecution execution(String id) {
        return (AgentExecution) Proxy.newProxyInstance(
                AgentExecution.class.getClassLoader(),
                new Class<?>[] { AgentExecution.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> id;
                    case "execute" -> CompletableFuture.completedFuture(
                            tech.kayys.andalus.agent.AgentResponse.success("runtime answer")
                                    .withMetadata("totalSteps", 3));
                    default -> null;
                });
    }

    @SuppressWarnings("unchecked")
    private Instance<AgentDefinitionResolver> resolverInstance(AgentDefinitionResolver resolver) {
        return (Instance<AgentDefinitionResolver>) Proxy.newProxyInstance(
                Instance.class.getClassLoader(),
                new Class<?>[] { Instance.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "isUnsatisfied" -> resolver == null;
                    case "isAmbiguous" -> false;
                    case "get" -> {
                        if (resolver == null) throw new IllegalStateException("unsatisfied");
                        yield resolver;
                    }
                    case "iterator" -> resolver == null ? java.util.Collections.emptyIterator()
                            : java.util.List.of(resolver).iterator();
                    default -> null;
                });
    }
}
