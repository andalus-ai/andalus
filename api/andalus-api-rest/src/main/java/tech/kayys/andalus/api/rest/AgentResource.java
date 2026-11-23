package tech.kayys.andalus.agent.api;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.security.Principal;
import java.util.HashMap;
import java.util.Set;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import tech.kayys.andalus.agent.core.core.AgentClient;
import tech.kayys.andalus.agent.spi.AgentRequest;
import tech.kayys.andalus.agent.spi.skills.SkillRegistry;

import java.util.List;
import java.util.Map;

/**
 * REST API over the active backend-agnostic agent contracts.
 */
@Path("/api/v1/agents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AgentResource {

    @Inject
    AgentClient agentClient;

    @Inject
    SkillRegistry skillRegistry;

    @Inject
    SecurityIdentity securityIdentity;

    private final AgentRunRequestMapper runRequestMapper = new AgentRunRequestMapper();
    private final AgentRunResponseMapper runResponseMapper = new AgentRunResponseMapper();
    private final AgentSkillCatalogService skillCatalogService = new AgentSkillCatalogService();

    @POST
    @Path("/run")
    public Uni<Response> run(AgentRunRequest request) {
        try {
            AgentRequest agentRequest = runRequestMapper.toAgentRequest(trustedRequest(request), false);
            return agentClient.execute(agentRequest)
                    .map(runResponseMapper::ok)
                    .onFailure().recoverWithItem(runResponseMapper::serverError);
        } catch (SecurityException error) {
            return Uni.createFrom().item(Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", error.getMessage())).build());
        } catch (RuntimeException error) {
            return Uni.createFrom().item(runResponseMapper.badRequest(error));
        }
    }

    @POST
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> stream(AgentRunRequest request) {
        try {
            return agentClient.execute(runRequestMapper.toAgentRequest(trustedRequest(request), true))
                    .onItem().transform(runResponseMapper::streamData)
                    .onFailure().recoverWithItem(runResponseMapper::streamError)
                    .toMulti();
        } catch (SecurityException error) {
            throw error;
        } catch (RuntimeException error) {
            return Multi.createFrom().item(runResponseMapper.streamError(error));
        }
    }


    /**
     * Client-supplied tenant/user fields are never identity sources. Bind them
     * to the verified Quarkus identity and discard identity-shaped context
     * attributes before passing the request to an agent implementation.
     */
    AgentRunRequest trustedRequest(AgentRunRequest request) {
        AgentRunRequest safe = request != null ? request : new AgentRunRequest(
                null, null, null, null, 0, null, null, null, null, null, null);
        SecurityIdentity identity = securityIdentity;
        boolean authenticated = identity != null && !identity.isAnonymous()
                && identity.getPrincipal() != null;
        String tenantId = null;
        String userId = null;
        if (authenticated) {
            Principal principal = identity.getPrincipal();
            userId = principal.getName();
            Object tenant = identity.getAttribute("tenantId");
            if (!(tenant instanceof String value) || value.isBlank()) {
                throw new SecurityException("Authenticated agent requests require a trusted tenantId");
            }
            tenantId = value;
        }

        Map<String, Object> context = new HashMap<>();
        if (safe.context() != null) context.putAll(safe.context());
        for (String key : Set.of("tenantId", "userId", "roles", "permissions", "principalId", "principalType")) {
            context.remove(key);
        }
        return new AgentRunRequest(safe.prompt(), safe.systemPrompt(), safe.strategy(), safe.skills(),
                safe.maxSteps(), safe.timeout(), tenantId, userId, safe.sessionId(),
                safe.modelId(), context);
    }

    @GET
    @Path("/skills")
    public List<SkillSummary> listSkills(@BeanParam AgentSkillsRequest request) {
        return skillCatalogService.listSkills(skillRegistry, request);
    }

    @GET
    @Path("/skills/{skillId}")
    public Response getSkill(@PathParam("skillId") String skillId) {
        return skillCatalogService.getSkill(skillRegistry, skillId)
                .map(skill -> Response.ok(skill).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Skill not found: " + skillId))
                        .build());
    }

    @GET
    @Path("/health")
    public AgentHealthResponse health() {
        return skillCatalogService.health(skillRegistry);
    }

    public record AgentRunRequest(
            String prompt,
            String systemPrompt,
            String strategy,
            List<String> skills,
            int maxSteps,
            String timeout,
            String tenantId,
            String userId,
            String sessionId,
            String modelId,
            Map<String, Object> context) {
    }

    public record SkillSummary(
            String id,
            String name,
            String description,
            String category,
            String version,
            int priority,
            boolean healthy,
            boolean runtime) {
    }

    public record AgentHealthResponse(
            String status,
            int totalSkills,
            int healthySkills,
            int unhealthySkills) {
    }
}
