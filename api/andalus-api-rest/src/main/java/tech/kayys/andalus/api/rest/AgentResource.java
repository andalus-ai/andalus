package tech.kayys.andalus.agent.api;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.security.Principal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
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
import tech.kayys.andalus.execution.AgentCallerContext;

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
            TrustedAgentRequest trusted = trustedCall(request);
            AgentRequest agentRequest = runRequestMapper.toAgentRequest(trusted.request(), false);
            return agentClient.execute(agentRequest, trusted.caller())
                    .map(runResponseMapper::ok)
                    .onFailure(UnsupportedOperationException.class).recoverWithItem(error ->
                            Response.status(Response.Status.NOT_IMPLEMENTED)
                                    .entity(Map.of("error", error.getMessage())).build())
                    .onFailure().recoverWithItem(runResponseMapper::serverError);
        } catch (ForbiddenException error) {
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
            TrustedAgentRequest trusted = trustedCall(request);
            AgentRequest agentRequest = runRequestMapper.toAgentRequest(trusted.request(), true);
            return agentClient.execute(agentRequest, trusted.caller())
                    .onItem().transform(runResponseMapper::streamData)
                    .onFailure().recoverWithItem(runResponseMapper::streamError)
                    .toMulti();
        } catch (ForbiddenException error) {
            throw error;
        } catch (RuntimeException error) {
            return Multi.createFrom().item(runResponseMapper.streamError(error));
        }
    }

    /**
     * Compatibility helper retained for request-mapping tests. Identity is
     * derived from SecurityIdentity, never from the request body.
     */
    AgentRunRequest trustedRequest(AgentRunRequest request) {
        return trustedCall(request).request();
    }

    /**
     * Creates the sanitized request and the caller snapshot together so both
     * values are derived from the same verified SecurityIdentity instance.
     */
    TrustedAgentRequest trustedCall(AgentRunRequest request) {
        AgentRunRequest safe = request != null ? request : new AgentRunRequest(
                null, null, null, null, 0, null, null, null, null, null, null);
        SecurityIdentity identity = securityIdentity;
        boolean authenticated = identity != null && !identity.isAnonymous()
                && identity.getPrincipal() != null;

        String tenantId = null;
        String userId = null;
        AgentCallerContext caller;
        if (authenticated) {
            Principal principal = identity.getPrincipal();
            userId = principal.getName();
            Object tenant = identity.getAttribute("tenantId");
            if (!(tenant instanceof String value) || value.isBlank()) {
                throw new ForbiddenException("Authenticated agent requests require a trusted tenantId");
            }
            tenantId = value;
            try {
                Set<String> roles = identity.getRoles() == null ? Set.of() : Set.copyOf(identity.getRoles());
                Set<String> permissions = trustedClaims(identity.getAttribute("permissions"), "permissions");
                caller = AgentCallerContext.user(tenantId, userId, roles, permissions);
            } catch (IllegalArgumentException error) {
                throw new ForbiddenException("Authenticated agent identity contains invalid authorization claims");
            }
        } else {
            caller = AgentCallerContext.anonymous(null);
        }

        Map<String, Object> context = new HashMap<>();
        if (safe.context() != null) context.putAll(safe.context());
        for (String key : Set.of("tenantId", "userId", "roles", "permissions", "principalId", "principalType")) {
            context.remove(key);
        }
        AgentRunRequest sanitized = new AgentRunRequest(safe.prompt(), safe.systemPrompt(), safe.strategy(),
                safe.skills(), safe.maxSteps(), safe.timeout(), tenantId, userId, safe.sessionId(),
                safe.modelId(), context);
        return new TrustedAgentRequest(sanitized, caller);
    }

    private Set<String> trustedClaims(Object value, String name) {
        if (value == null) {
            return Set.of();
        }
        if (!(value instanceof Set<?> values)) {
            throw new IllegalArgumentException(name + " must be a Set of concrete strings");
        }
        Set<String> claims = new LinkedHashSet<>();
        for (Object item : values) {
            if (!(item instanceof String claim) || claim.isBlank() || "*".equals(claim.trim())) {
                throw new IllegalArgumentException(name + " must contain only concrete, non-blank strings");
            }
            claims.add(claim);
        }
        return Set.copyOf(claims);
    }

    record TrustedAgentRequest(AgentRunRequest request, AgentCallerContext caller) {
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
