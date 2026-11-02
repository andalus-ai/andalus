package tech.kayys.andalus.coordination.discovery;

import java.util.Objects;

/**
 * Tenant-scoped lifecycle signal carrying an authoritative fencing token.
 *
 * The host/control plane assigns ownerId and generation. Generation must increase
 * for each state transition or ownership change; HEALTHY renewals retain the token.
 */
public record AgentRuntimeLifecycleEvent(
        String agentId,
        String tenantId,
        String ownerId,
        long generation,
        Type type,
        int priority,
        double costFactor) {

    public AgentRuntimeLifecycleEvent {
        agentId = requireText(agentId, "agentId");
        tenantId = requireText(tenantId, "tenantId");
        ownerId = requireText(ownerId, "ownerId");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        type = Objects.requireNonNull(type, "type must not be null");
        if (!Double.isFinite(costFactor) || costFactor < 0)
            throw new IllegalArgumentException("costFactor must be finite and non-negative");
    }

    public enum Type { STARTED, HEALTHY, UNHEALTHY, STOPPED }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
