package tech.kayys.andalus.agent.api;

import io.smallrye.mutiny.Uni;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.agent.core.core.AgentClient;
import tech.kayys.andalus.agent.spi.AgentRequest;
import tech.kayys.andalus.agent.spi.AgentResponse;
import tech.kayys.andalus.agent.spi.AgentSkill;
import tech.kayys.andalus.agent.spi.skills.SkillCategory;
import tech.kayys.andalus.agent.spi.skills.SkillDefinition;
import tech.kayys.andalus.agent.spi.skills.SkillRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class AgentResourceTest {

    @Test
    void runMapsRequestAndTrustedCallerToActiveAgentClient() {
        RecordingAgentClient client = new RecordingAgentClient();
        AgentResource resource = resource(client, registry());

        Response response = resource.run(new AgentResource.AgentRunRequest(
                "hello", "system", "react", List.of("echo"), 4, "PT5S",
                "tenant-a", "user-a", "session-a", "test-model",
                Map.of("traceId", "trace-1"), "agent-a")).await().indefinitely();

        assertThat(response.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertThat(body).containsEntry("answer", "done").containsEntry("successful", true);
        assertThat(client.agentId).isEqualTo("agent-a");
        assertThat(client.lastRequest.prompt()).isEqualTo("hello");
        assertThat(client.lastRequest.modelId()).isEqualTo("test-model");
        assertThat(client.lastRequest.requestId()).isNotBlank();
        assertThat(client.lastRequest.tenantId()).isEqualTo("tenant-a");
        assertThat(client.lastRequest.userId()).isEqualTo("user-a");
        assertThat(client.lastRequest.sessionId()).isEqualTo("session-a");
        assertThat(client.caller.tenantId()).isEqualTo("tenant-a");
        assertThat(client.caller.userId()).isEqualTo("user-a");
    }

    @Test
    void runRejectsMissingPrompt() {
        AgentResource resource = resource(new RecordingAgentClient(), registry());

        Response response = resource.run(new AgentResource.AgentRunRequest(
                "", null, null, null, 0, null,
                null, null, null, null, null, "agent-a")).await().indefinitely();

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getEntity()).isEqualTo(Map.of("error", "Prompt is required"));
    }

    @Test
    void runRequiresExplicitAgentId() {
        AgentResource resource = resource(new RecordingAgentClient(), registry());

        Response response = resource.run(new AgentResource.AgentRunRequest(
                "hello", null, null, null, 0, null,
                null, null, null, null, null)).await().indefinitely();

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getEntity()).isEqualTo(Map.of("error", "agentId is required"));
    }

    @Test
    void runPassesSelectedStrategyThroughClientContract() {
        RecordingAgentClient client = new RecordingAgentClient();
        AgentResource resource = resource(client, registry());

        Response response = resource.run(new AgentResource.AgentRunRequest(
                "persist this workflow", null, "hermes-agent", List.of(), 0, null,
                "tenant-a", "user-a", "session-a", null, null, "agent-a"))
                .await().indefinitely();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(client.lastRequest.strategy().id).isEqualTo("hermes-agent");
        assertThat(client.lastRequest.tenantId()).isEqualTo("tenant-a");
        assertThat(client.lastRequest.sessionId()).isEqualTo("session-a");
    }

    @Test
    void listSkillsAndHealthUseRuntimeAndDefinitionRegistries() {
        AgentApiTestSkillRegistry registry = registry();
        registry.register(new EchoSkill());
        registry.registerSkill(SkillDefinition.builder()
                .id("planner")
                .name("Planner")
                .description("Plans tasks")
                .category("REASONING")
                .systemPrompt("Plan carefully.")
                .metadata(Map.of("version", "2.0.0", "priority", 12))
                .build());
        AgentResource resource = resource(new RecordingAgentClient(), registry);

        assertThat(resource.listSkills(null)).extracting(AgentResource.SkillSummary::id)
                .containsExactly("echo", "planner");
        assertThat(resource.listSkills(new AgentSkillsRequest("REASONING"))).extracting(AgentResource.SkillSummary::id)
                .containsExactly("planner");
        assertThat(resource.getSkill("planner").getStatus()).isEqualTo(200);
        assertThat(resource.getSkill("missing").getStatus()).isEqualTo(404);
        assertThat(resource.health())
                .isEqualTo(new AgentResource.AgentHealthResponse("UP", 2, 1, 1));
    }

    @Test
    void authenticatedIdentityOverridesClientTenantAndUserClaims() {
        AgentResource resource = resource(new RecordingAgentClient(), registry());
        AgentResource.AgentRunRequest trusted = resource.trustedRequest(new AgentResource.AgentRunRequest(
                "hello", null, null, List.of(), 1, null,
                "attacker-tenant", "attacker-user", null, null,
                Map.of("tenantId", "attacker-tenant", "userId", "attacker-user",
                        "roles", List.of("admin"), "traceId", "trace-1")));

        assertThat(trusted.tenantId()).isEqualTo("tenant-a");
        assertThat(trusted.userId()).isEqualTo("user-a");
        assertThat(trusted.context()).containsOnlyKeys("traceId");
    }

    @Test
    void authenticatedIdentityWithoutTenantIsRejected() {
        AgentResource resource = resource(new RecordingAgentClient(), registry());
        resource.securityIdentity = identity(null);
        Response response = resource.run(new AgentResource.AgentRunRequest(
                "hello", null, null, null, 0, null,
                "forged-tenant", "forged-user", null, null, null, "agent-a"))
                .await().indefinitely();

        assertThat(response.getStatus()).isEqualTo(403);
    }

    private AgentResource resource(AgentClient client, SkillRegistry registry) {
        AgentResource resource = new AgentResource();
        resource.agentClient = client;
        resource.skillRegistry = registry;
        resource.securityIdentity = identity("tenant-a");
        return resource;
    }

    private SecurityIdentity identity(String tenantId) {
        return (SecurityIdentity) Proxy.newProxyInstance(
                SecurityIdentity.class.getClassLoader(),
                new Class<?>[] { SecurityIdentity.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "isAnonymous" -> false;
                    case "getPrincipal" -> (Principal) () -> "user-a";
                    case "getAttribute" -> "tenantId".equals(args[0]) ? tenantId : null;
                    case "getRoles" -> Set.of("agent:run");
                    case "hasRole" -> false;
                    default -> null;
                });
    }

    private AgentApiTestSkillRegistry registry() {
        return new AgentApiTestSkillRegistry();
    }

    private static final class RecordingAgentClient implements AgentClient {
        private AgentRequest lastRequest;
        private tech.kayys.andalus.execution.AgentCallerContext caller;
        private String agentId;

        @Override
        public Uni<AgentResponse> execute(AgentRequest request) {
            return Uni.createFrom().failure(new AssertionError("trusted caller overload required"));
        }

        @Override
        public Uni<AgentResponse> execute(AgentRequest request,
                tech.kayys.andalus.execution.AgentCallerContext caller, String agentId) {
            this.lastRequest = request;
            this.caller = caller;
            this.agentId = agentId;
            String strategy = request.strategy() != null ? request.strategy().id : "unknown";
            String answer = "hermes-agent".equals(strategy) ? "handled by hermes-agent" : "done";
            return Uni.createFrom().item(AgentResponse.builder()
                    .runId("run-1")
                    .requestId(request.requestId())
                    .answer(answer)
                    .successful(true)
                    .strategy(strategy)
                    .build());
        }
    }

    private static final class EchoSkill implements AgentSkill {
        @Override public String id() { return "echo"; }
        @Override public String name() { return "Echo"; }
        @Override public String description() { return "Echoes text"; }
        @Override public String category() { return SkillCategory.EXECUTION.name(); }
        @Override public boolean isHealthy() { return false; }
        @Override public Uni<Map<String, Object>> execute(Map<String, Object> context) {
            return Uni.createFrom().item(Map.of("success", true));
        }
    }
}
