package tech.kayys.andalus.coordination.discovery;

import java.time.Instant;
import java.util.Objects;

/**
 * Tenant-scoped, time-bounded assertion of an agent's operational routing state.
 *
 * The generation is a fencing token assigned by an authoritative host/control plane.
 * It must increase whenever ownership changes; it must not be generated independently
 * by each runtime process.
 */
public record AgentRuntimeStateLease(
        String agentId,
        String tenantId,
        AgentRuntimeState state,
        Instant expiresAt,
        String ownerId,
        long generation) {

    /** Compatibility constructor for single-process callers predating fencing tokens. */
    public AgentRuntimeStateLease(
            String agentId, String tenantId, AgentRuntimeState state, Instant expiresAt) {
        this(agentId, tenantId, state, expiresAt, "legacy", 0);
    }

    public AgentRuntimeStateLease {
        agentId = requireText(agentId, "agentId");
        tenantId = requireText(tenantId, "tenantId");
        state = Objects.requireNonNull(state, "state must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        ownerId = requireText(ownerId, "ownerId");
        if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
