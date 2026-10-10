package tech.kayys.andalus.api.rest.execution;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import tech.kayys.andalus.agent.kernel.interaction.AgentInteractionService;

/**
 * REST projection of the agent interaction kernel.
 */
@Path("/api/v1/agent-interactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AgentInteractionRestResource {

    @Inject
    AgentInteractionService interactions;

    @POST
    @Path("/{runId}/context")
    public Response open(@PathParam("runId") String runId) {
        interactions.context(runId);
        return Response.ok(interactions.snapshot(runId)).build();
    }

    @GET
    @Path("/{runId}")
    public Response snapshot(@PathParam("runId") String runId) {
        return Response.ok(interactions.snapshot(runId)).build();
    }

    @GET
    @Path("/{runId}/events")
    public Response events(@PathParam("runId") String runId) {
        return Response.ok(Map.of(
                "runId", runId, "events", interactions.history(runId))).build();
    }
}
