package tech.kayys.andalus.coordination.discovery;

import java.util.Set;

/**
 * Capability and operational constraints for selecting a delegation target.
 *
 * @param requiredCapabilities capabilities that must all be present
 * @param preferredCapabilities capabilities that improve a candidate's rank
 * @param maxCostFactor optional inclusive upper bound for candidate cost
 */
public record TaskCapabilityRequirements(
        Set<String> requiredCapabilities,
        Set<String> preferredCapabilities,
        Double maxCostFactor
) {
    public TaskCapabilityRequirements {
        requiredCapabilities = requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities);
        preferredCapabilities = preferredCapabilities == null ? Set.of() : Set.copyOf(preferredCapabilities);
        if (maxCostFactor != null && (!Double.isFinite(maxCostFactor) || maxCostFactor < 0)) {
            throw new IllegalArgumentException("maxCostFactor must be finite and non-negative");
        }
    }

    public static TaskCapabilityRequirements requiring(Set<String> capabilities) {
        return new TaskCapabilityRequirements(capabilities, Set.of(), null);
    }
}
