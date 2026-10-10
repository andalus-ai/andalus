package tech.kayys.andalus.hitl.resource;

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
import tech.kayys.andalus.agent.kernel.interaction.ApprovalRequest;
import tech.kayys.andalus.agent.kernel.interaction.InputRequest;
import tech.kayys.andalus.agent.kernel.interaction.Presentation;

/**
 * Runtime projection of the framework interaction kernel.
 */
@Path("/api/v1/agent-interactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AgentInteractionResource {

    @Inject
    AgentInteractionService interactions;

    @POST
    @Path("/{runId}/context")
    public Response openContext(@PathParam("runId") String runId) {
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
                "runId", runId,
                "events", interactions.history(runId))).build();
    }

    @POST
    @Path("/{runId}/approvals")
    public Response requestApproval(
            @PathParam("runId") String runId, ApprovalPayload payload) {
        ApprovalRequest req = interactions.controller().requestApproval(
                runId,
                payload == null || payload.reason() == null ? "approval-required" : payload.reason(),
                payload == null ? null : payload.capability(),
                payload == null || payload.context() == null ? Map.of() : payload.context());
        return Response.ok(Map.of(
                "approvalId", req.id(),
                "runId", req.runId(),
                "state", interactions.state(runId).name())).build();
    }

    @POST
    @Path("/approvals/{approvalId}/grant")
    public Response grant(
            @PathParam("approvalId") String approvalId, ActorPayload payload) {
        var decision = interactions.controller().grant(
                approvalId, payload == null ? "anonymous" : payload.actor());
        return Response.ok(Map.of(
                "approvalId", decision.approvalId(),
                "status", decision.status().name())).build();
    }

    public record ApprovalPayload(String reason, String capability, Map<String, Object> context) {}
    public record ActorPayload(String actor) {}

    @POST
    @Path("/approvals/{approvalId}/reject")
    public Response reject(
            @PathParam("approvalId") String approvalId, RejectPayload payload) {
        var decision = interactions.controller().reject(
                approvalId,
                payload == null ? "anonymous" : payload.actor(),
                payload == null ? null : payload.reason());
        return Response.ok(Map.of(
                "approvalId", decision.approvalId(),
                "status", decision.status().name())).build();
    }

    @POST
    @Path("/{runId}/inputs")
    public Response requestInput(
            @PathParam("runId") String runId, InputPayload payload) {
        InputRequest req = interactions.controller().requestInput(
                runId,
                payload == null || payload.prompt() == null ? "input-required" : payload.prompt(),
                payload == null || payload.inputType() == null ? "text" : payload.inputType(),
                payload == null || payload.schema() == null ? Map.of() : payload.schema(),
                payload == null || payload.required());
        return Response.ok(Map.of(
                "inputId", req.id(),
                "runId", req.runId(),
                "state", interactions.state(runId).name())).build();
    }

    @POST
    @Path("/inputs/{inputId}/answer")
    public Response answer(
            @PathParam("inputId") String inputId, AnswerPayload payload) {
        var resp = interactions.controller().answer(
                inputId, payload == null ? null : payload.value());
        return Response.ok(Map.of(
                "inputId", resp.requestId(),
                "runId", resp.runId())).build();
    }

    @POST
    @Path("/{runId}/presentations")
    public Response present(
            @PathParam("runId") String runId, PresentationPayload payload) {
        String format = payload == null || payload.format() == null
                ? "MARKDOWN" : payload.format().toUpperCase();
        String pid = payload == null || payload.id() == null ? "p" : payload.id();
        Object content = payload == null ? "" : payload.content();
        Presentation presentation = switch (format) {
            case "TEXT" -> Presentation.text(pid, String.valueOf(content));
            case "JSON" -> Presentation.json(pid, content);
            default -> Presentation.markdown(pid, String.valueOf(content));
        };
        var event = interactions.controller().present(runId, presentation);
        return Response.ok(Map.of(
                "sequence", event.sequence(),
                "type", event.type().name())).build();
    }

    @POST
    @Path("/{runId}/surfaces/{surfaceId}/open")
    public Response openSurface(
            @PathParam("runId") String runId,
            @PathParam("surfaceId") String surfaceId) {
        var surface = interactions.surface(runId, surfaceId);
        return Response.ok(Map.of(
                "runId", runId,
                "surfaceId", surface.id(),
                "state", surface.state())).build();
    }

    @POST
    @Path("/{runId}/surfaces/{surfaceId}/state")
    public Response updateSurface(
            @PathParam("runId") String runId,
            @PathParam("surfaceId") String surfaceId,
            Map<String, Object> values) {
        var next = interactions.ui().update(
                runId, surfaceId, values == null ? Map.of() : values);
        return Response.ok(Map.of(
                "surfaceId", next.surfaceId(),
                "version", next.version(),
                "values", next.values())).build();
    }

    @GET
    @Path("/{runId}/surfaces/{surfaceId}")
    public Response getSurface(
            @PathParam("runId") String runId,
            @PathParam("surfaceId") String surfaceId) {
        return Response.ok(interactions.ui().state(runId, surfaceId)).build();
    }

    @POST
    @Path("/{runId}/surfaces/{surfaceId}/present")
    public Response presentToSurface(
            @PathParam("runId") String runId,
            @PathParam("surfaceId") String surfaceId,
            PresentationPayload payload) {
        var surface = interactions.surface(runId, surfaceId);
        String format = payload == null || payload.format() == null
                ? "MARKDOWN" : payload.format().toUpperCase();
        String pid = payload == null || payload.id() == null ? "p" : payload.id();
        Object content = payload == null ? "" : payload.content();
        Presentation presentation = switch (format) {
            case "TEXT" -> Presentation.text(pid, String.valueOf(content));
            case "JSON" -> Presentation.json(pid, content);
            default -> Presentation.markdown(pid, String.valueOf(content));
        };
        surface.present(presentation);
        return Response.ok(Map.of(
                "surfaceId", surfaceId,
                "state", surface.state())).build();
    }

    public record RejectPayload(String actor, String reason) {}
    public record InputPayload(String prompt, String inputType, Map<String, Object> schema, boolean required) {}
    public record AnswerPayload(Object value) {}
    public record PresentationPayload(String id, String format, Object content) {}
}
