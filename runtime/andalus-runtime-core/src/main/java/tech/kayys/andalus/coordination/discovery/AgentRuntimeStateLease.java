package tech.kayys.andalus.coordination.discovery;

import java.time.Instant;
import java.util.Objects;

/** Tenant-scoped, time-bounded assertion of an agent's operational routing state. */
public record AgentRuntimeStateLease(
        String agentId, String tenantId, AgentRuntimeState state, Instant expiresAt) {
    public AgentRuntimeStateLease {
        agentId = requireText(agentId, "agentId");
        tenantId = requireText(tenantId, "tenantId");
        state = Objects.requireNonNull(state, "state must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
