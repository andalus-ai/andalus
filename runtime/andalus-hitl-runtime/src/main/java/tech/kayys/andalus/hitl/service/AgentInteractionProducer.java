package tech.kayys.andalus.hitl.service;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import tech.kayys.andalus.agent.kernel.interaction.AgentInteractionService;

/**
 * CDI bridge exposing the framework Phase-4 interaction kernel
 * to the HITL runtime.
 *
 * <p>Existing {@link HumanTaskService} remains the durable
 * workflow-task store; this bean holds the ephemeral per-run
 * approval/input/presentation state and drives pause/resume
 * semantics via the agent execution controller.</p>
 */
@ApplicationScoped
public class AgentInteractionProducer {

    @Produces
    @ApplicationScoped
    public AgentInteractionService agentInteractionService() {
        return AgentInteractionService.inMemory();
    }
}
