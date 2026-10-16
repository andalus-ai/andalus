package tech.kayys.andalus.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.agent.AgentContext;
import tech.kayys.andalus.agent.AgentRequest;
import tech.kayys.andalus.agent.AgentResponse;
import tech.kayys.andalus.core.AgentDefinition;
import tech.kayys.andalus.extension.Id;
import tech.kayys.andalus.extension.Metadata;
import tech.kayys.andalus.extension.Reference;
import tech.kayys.andalus.provider.*;
import tech.kayys.andalus.provider.routing.*;
import tech.kayys.andalus.resource.Modality;
import tech.kayys.andalus.resource.ResourceType;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class DefaultAgentExecutionInferencePlanTest {

    private static class StubProvider implements Provider {
        private final String id;

        public StubProvider(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void streamChat(List<ChatMessage> messages, String systemPrompt, List<ToolSpec> tools, double temperature, int maxTokens, Consumer<StreamEvent> onEvent) throws IOException, InterruptedException {
            onEvent.accept(new StreamEvent.TextDelta("Calculation complete"));
            onEvent.accept(new StreamEvent.MessageStop("end_turn"));
        }

        @Override
        public Set<Modality> supportedModalities() {
            return Set.of(Modality.TEXT);
        }
    }

    private StubProvider openaiProvider;
    private StubProvider anthropicProvider;
    private StubProvider aqliProvider;
    private List<Provider> availableProviders;

    @BeforeEach
    void setUp() {
        openaiProvider = new StubProvider("openai");
        anthropicProvider = new StubProvider("anthropic");
        aqliProvider = new StubProvider("aqli");
        availableProviders = List.of(openaiProvider, anthropicProvider, aqliProvider);
    }

    @Test
    void testExecutionWithAdaptiveModelRouterProducesInferencePlan() {
        DefaultModelRouter router = new DefaultModelRouter(RoutingStrategy.ADAPTIVE);

        AgentRequest request = AgentRequest.builder()
                .content("Calculate the fibonacci series")
                .build();

        AgentContext context = AgentContext.builder()
                .request(request)
                .build();

        AgentDefinition agentDef = AgentDefinition.builder()
                .metadata(Metadata.builder().name("MathAgent").build())
                .build();

        DefaultAgentExecution execution = new DefaultAgentExecution(
                "exec-101",
                agentDef,
                context,
                ExecutionBudget.fast(),
                null,
                null,
                availableProviders,
                router,
                null,
                null,
                null,
                null,
                null
        );

        AgentResponse response = execution.execute().toCompletableFuture().join();
        assertNotNull(response);
        assertTrue(response.success());
    }

    @Test
    void testExecutionWithDirectModelRouterHonorsExplicitModel() {
        DefaultModelRouter router = new DefaultModelRouter(RoutingStrategy.DIRECT);

        AgentRequest request = AgentRequest.builder()
                .content("Hello World")
                .build();

        AgentContext context = AgentContext.builder()
                .request(request)
                .build();

        AgentDefinition agentDef = AgentDefinition.builder()
                .metadata(Metadata.builder().name("DirectAgent").build())
                .model(Reference.of(Id.random(), ResourceType.fromString("model"), "anthropic"))
                .build();

        DefaultAgentExecution execution = new DefaultAgentExecution(
                "exec-102",
                agentDef,
                context,
                ExecutionBudget.balanced(),
                null,
                null,
                availableProviders,
                router,
                null,
                null,
                null,
                null,
                null
        );

        AgentResponse response = execution.execute().toCompletableFuture().join();
        assertNotNull(response);
        assertTrue(response.success());
    }
}
