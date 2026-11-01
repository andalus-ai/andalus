package tech.kayys.andalus.coordination.discovery;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic, policy-neutral candidate ranking for agent delegation.
 *
 * <p>The selector filters unavailable agents, candidates missing any required
 * capability, and candidates above the optional cost ceiling. Remaining
 * candidates are ordered by preferred-capability coverage (descending),
 * configured priority (descending), cost (ascending), then agent id. The
 * caller remains responsible for authorization, budget enforcement, and
 * actually dispatching work.</p>
 */
public final class CapabilityBasedAgentSelector {

    public List<AgentCapabilityCandidate> select(
            TaskCapabilityRequirements requirements,
            List<AgentCapabilityCandidate> candidates) {
        Objects.requireNonNull(requirements, "requirements must not be null");
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(AgentCapabilityCandidate::available)
                .filter(candidate -> candidate.capabilities().containsAll(requirements.requiredCapabilities()))
                .filter(candidate -> requirements.maxCostFactor() == null
                        || candidate.costFactor() <= requirements.maxCostFactor())
                .sorted(Comparator
                        .<AgentCapabilityCandidate>comparingInt(candidate ->
                                preferredCoverage(candidate, requirements)).reversed()
                        .thenComparing(Comparator.comparingInt(AgentCapabilityCandidate::priority).reversed())
                        .thenComparingDouble(AgentCapabilityCandidate::costFactor)
                        .thenComparing(AgentCapabilityCandidate::agentId))
                .toList();
    }

    private int preferredCoverage(
            AgentCapabilityCandidate candidate,
            TaskCapabilityRequirements requirements) {
        return (int) requirements.preferredCapabilities().stream()
                .filter(candidate.capabilities()::contains)
                .count();
    }
}
