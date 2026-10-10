package tech.kayys.andalus.hitl.resource;

import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.agent.kernel.interaction.AgentInteractionService;

import static org.junit.jupiter.api.Assertions.*;

class AgentInteractionResourceTest {

    private AgentInteractionResource resource;

    @BeforeEach
    void setUp() {
        resource = new AgentInteractionResource();
        resource.interactions = AgentInteractionService.inMemory();
    }

    @Test
    void approvalAndInputFlow() {
        Response ctx = resource.openContext("run-1");
        assertEquals(200, ctx.getStatus());

        var approvalId = (String) ((Map<?, ?>) resource.requestApproval(
                "run-1",
                new AgentInteractionResource.ApprovalPayload("review", "shell", Map.of()))
                .getEntity()).get("approvalId");
        Response grant = resource.grant(
                approvalId, new AgentInteractionResource.ActorPayload("ops"));
        assertEquals(200, grant.getStatus());

        var inputId = (String) ((Map<?, ?>) resource.requestInput(
                "run-1",
                new AgentInteractionResource.InputPayload("name?", "text", Map.of(), true))
                .getEntity()).get("inputId");
        Response answer = resource.answer(
                inputId, new AgentInteractionResource.AnswerPayload("Ada"));
        assertEquals(200, answer.getStatus());

        Response events = resource.events("run-1");
        assertEquals(200, events.getStatus());
    }

    @Test
    void surfaceFlow() {
        Response opened = resource.openSurface("run-2", "workbench");
        assertEquals(200, opened.getStatus());

        Response updated = resource.updateSurface(
                "run-2", "workbench", Map.of("priority", "HIGH"));
        assertEquals(200, updated.getStatus());

        Response presented = resource.presentToSurface(
                "run-2", "workbench",
                new AgentInteractionResource.PresentationPayload("p1", "MARKDOWN", "# hi"));
        assertEquals(200, presented.getStatus());

        Response fetched = resource.getSurface("run-2", "workbench");
        assertEquals(200, fetched.getStatus());
    }
}
