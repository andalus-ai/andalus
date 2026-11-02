package tech.kayys.andalus.coordination.discovery;

/**
 * Live operational state used when projecting a registered agent into
 * delegation discovery. This is deliberately separate from its descriptive
 * framework profile.
 */
public record AgentRuntimeState(
        boolean available,
        int priority,
        double costFactor
) {
    public AgentRuntimeState {
        if (!Double.isFinite(costFactor) || costFactor < 0) {
            throw new IllegalArgumentException("costFactor must be finite and non-negative");
        }
    }
}
