package tech.kayys.andalus.cli;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.agent.run.AgentRunRequest;
import tech.kayys.andalus.agent.run.AgentRunResult;
import tech.kayys.andalus.agent.skill.AgentSkillQuery;
import tech.kayys.andalus.client.ProductSurface;
import tech.kayys.andalus.skill.RegisteredSkill;
import tech.kayys.andalus.client.AndalusAqliSdk;
import tech.kayys.andalus.client.AndalusPlatformStatus;
import tech.kayys.andalus.client.AndalusWorkbenchModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AndalusAqliCliSdkTest {

    private final AndalusAqliSdk service = AndalusAqliSdk.local();

    @Test
    void describesAndalusAsPlatformAboveAqliAndGamelan() {
        AndalusPlatformStatus status = service.status();

        assertThat(status.productName()).isEqualTo("Andalus");
        assertThat(status.aqli().role()).contains("Inference");
        assertThat(status.gamelan().role()).contains("Workflow");
        assertThat(status.notes()).anySatisfy(note -> assertThat(note).contains("SDK"));
        assertThat(status.notes()).anySatisfy(note -> assertThat(note).contains("separate from Aqli CLI"));
        assertThat(status.notes()).anySatisfy(note -> assertThat(note).contains("agent workbench"));
        assertThat(status.activeSkills()).isEqualTo(12);
    }

    @Test
    void preparesAgentRunThroughAndalusBoundaries() {
        AgentRunResult result = service.run(new AgentRunRequest(
                " plan next eval ",
                " tenant-a ",
                " model-a ",
                " workflow-a ",
                List.of("rag"),
                true,
                5));

        assertThat(result.successful()).isTrue();
        assertThat(result.answer()).contains("plan next eval");
        assertThat(result.metadata())
                .containsEntry("tenant", "tenant-a")
                .containsEntry("model", "model-a")
                .containsEntry("workflow", "workflow-a")
                .containsEntry("surface", "coding-agent")
                .containsEntry("memoryEnabled", true)
                .containsEntry("maxSteps", 5);
    }

    @Test
    void exposesProductSurfacesPoweredByCoreEngine() {
        assertThat(service.productSurfaces())
                .extracting(ProductSurface::id)
                .containsExactly("coding-agent", "assistant-agent", "workflow-platform", "platform-admin");
        assertThat(service.productSurfacePolicy("coding-agent").routingHints())
                .contains("inspect-workspace", "plan-harness");
        assertThat(service.assessRunPolicy(AgentRunRequest.builder()
                .prompt("plan")
                .surfaceId("workflow-platform")
                .build()).recommendations())
                .contains("Set a workflow id with --workflow <id>.");
    }

    @Test
    void buildsWorkbenchModelForPlainAndTambouiRenderers() {
        AndalusWorkbenchModel model = service.workbench();

        assertThat(model.status().productName()).isEqualTo("Andalus");
        assertThat(model.productSurfaces())
                .extracting(ProductSurface::id)
                .contains("coding-agent", "workflow-platform");
        assertThat(model.commandPalette())
                .contains("workbench", "tui")
                .anySatisfy(command -> assertThat(command).contains("run <task>"));
        assertThat(model.commands())
                .anySatisfy(command -> {
                    assertThat(command.id()).isEqualTo("run-workflow-skill");
                    assertThat(command.surfaceIds()).containsExactly("workflow-platform");
                });
        assertThat(model.nextActions())
                .anySatisfy(action -> assertThat(action).contains("Tamboui"));
    }

    @Test
    void exposesDefaultSkillRegistryForProductSurfaces() {
        assertThat(service.skills())
                .extracting(RegisteredSkill::id)
                .contains("repo.context", "rag.retrieve", "mcp.bridge", "workflow.gamelan");

        assertThat(service.skill("rag").id()).isEqualTo("rag.retrieve");

        assertThat(service.skills(new AgentSkillQuery(
                "assistant-agent",
                null,
                "rag",
                null,
                null,
                null,
                "query",
                "citations")))
                .singleElement()
                .satisfies(skill -> {
                    assertThat(skill.id()).isEqualTo("rag.retrieve");
                    assertThat(skill.descriptor().surfaceIds()).contains("assistant-agent");
                    assertThat(skill.descriptor().tags()).contains("rag", "docs");
                });
    }
}
