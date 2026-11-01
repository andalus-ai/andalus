package tech.kayys.andalus.coordination.discovery;

import java.util.List;
import java.util.Objects;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import tech.kayys.andalus.coordination.CoordinationContext;
import tech.kayys.andalus.coordination.CoordinationRequest;

/**
 * Connects runtime candidate discovery to deterministic delegation ranking.
 *
 * <p>This component plans a target only. Dispatch, authorization, budget
 * reservation, and tenant policy enforcement remain with the coordinator.</p>
 */
@ApplicationScoped
public class AgentDelegationPlanner {

    private final AgentCapabilityDirectory directory;
    private final CapabilityBasedAgentSelector selector;

    @Inject
    public AgentDelegationPlanner(AgentCapabilityDirectory directory) {
        this(directory, new CapabilityBasedAgentSelector());
    }

    AgentDelegationPlanner(
            AgentCapabilityDirectory directory,
            CapabilityBasedAgentSelector selector) {
        this.directory = Objects.requireNonNull(directory, "directory must not be null");
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
    }

    public List<AgentCapabilityCandidate> plan(
            CoordinationRequest request,
            CoordinationContext context,
            TaskCapabilityRequirements requirements) {
        Objects.requireNonNull(requirements, "requirements must not be null");
        return selector.select(requirements, directory.discover(request, context));
    }
}
