package tech.kayys.andalus.coordination.discovery;

import java.util.HashSet;
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
        agentId = requireText(agentId, "agentId");
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        Set<String> capabilityIds = new HashSet<>();
        for (String capability : capabilities) {
            String normalized = requireText(capability, "capability");
            if (!capability.equals(normalized)) {
                throw new IllegalArgumentException("capability identifiers must not contain surrounding whitespace");
            }
            if (!capabilityIds.add(normalized)) {
                throw new IllegalArgumentException("duplicate capability identifier '" + normalized + "'");
            }
        }
        if (!Double.isFinite(costFactor) || costFactor < 0) {
            throw new IllegalArgumentException("costFactor must be finite and non-negative");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
