package tech.kayys.andalus.coordination.discovery;

import java.util.Objects;

/** Tenant-scoped lifecycle signal emitted by an agent host after an operational transition. */
public record AgentRuntimeLifecycleEvent(
        String agentId, String tenantId, Type type, int priority, double costFactor) {
    public AgentRuntimeLifecycleEvent {
        agentId = requireText(agentId, "agentId");
        tenantId = requireText(tenantId, "tenantId");
        type = Objects.requireNonNull(type, "type must not be null");
        if (!Double.isFinite(costFactor) || costFactor < 0)
            throw new IllegalArgumentException("costFactor must be finite and non-negative");
    }
    public enum Type { STARTED, HEALTHY, UNHEALTHY, STOPPED }
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
