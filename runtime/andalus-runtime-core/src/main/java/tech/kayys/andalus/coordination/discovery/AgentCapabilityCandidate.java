package tech.kayys.andalus.coordination.discovery;

import java.util.Set;

/**
 * Runtime view of an agent that may receive delegated work.
 *
 * <p>Capabilities are stable, namespaced identifiers (for example
 * {@code research.web-search}). This descriptor is routing metadata only;
 * it does not grant permission to invoke the agent.</p>
 */
public record AgentCapabilityCandidate(
        String agentId,
        Set<String> capabilities,
        boolean available,
        int priority,
        double costFactor
) {
    public AgentCapabilityCandidate {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        if (!Double.isFinite(costFactor) || costFactor < 0) {
            throw new IllegalArgumentException("costFactor must be finite and non-negative");
        }
    }
}
