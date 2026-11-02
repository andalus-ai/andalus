package tech.kayys.andalus.coordination.discovery;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.andalus.coordination.CoordinationContext;
import tech.kayys.andalus.coordination.CoordinationRequest;

/**
 * In-memory lease-backed state provider for a single runtime process.
 * Fencing semantics are suitable for shared-provider implementations too, but
 * multi-instance deployments still require durable shared storage and atomic CAS.
 */
@ApplicationScoped
public class InMemoryAgentRuntimeStateProvider implements AgentRuntimeStateProvider {
    private final Map<Key, AgentRuntimeStateLease> leases = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryAgentRuntimeStateProvider() { this(Clock.systemUTC()); }

    InMemoryAgentRuntimeStateProvider(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Publishes a lease if its fencing token is current. A newer generation replaces
     * an older one. The same owner/generation may only renew an unchanged state;
     * changing state requires a strictly newer generation.
     *
     * @return false when the lease is stale or conflicts with the current owner/token
     */
    public boolean publish(AgentRuntimeStateLease lease) {
        validateLease(lease);
        Key key = new Key(lease.tenantId(), lease.agentId());
        final boolean[] accepted = {false};
        leases.compute(key, (ignored, current) -> {
            if (current == null || lease.generation() > current.generation()) {
                accepted[0] = true;
                return lease;
            }
            // Preserve the pre-fencing single-process API for legacy callers. Fenced
            // hosts must use explicit owner IDs and positive generations.
            if (lease.ownerId().equals("legacy") && current.ownerId().equals("legacy")) {
                accepted[0] = true;
                return lease;
            }
            if (lease.generation() < current.generation()
                    || !lease.ownerId().equals(current.ownerId())) return current;

            // A token can renew an unchanged state, but cannot reorder transitions.
            if (!lease.state().equals(current.state())) return current;
            if (lease.expiresAt().isAfter(current.expiresAt())) {
                accepted[0] = true;
                return lease;
            }
            return current;
        });
        return accepted[0];
    }

    /** Removes a lease only when both owner and generation still match. */
    public boolean revoke(String tenantId, String agentId, String ownerId, long generation) {
        Key key = new Key(requireText(tenantId, "tenantId"), requireText(agentId, "agentId"));
        String owner = requireText(ownerId, "ownerId");
        if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
        final boolean[] removed = {false};
        leases.computeIfPresent(key, (ignored, current) -> {
            if (current.generation() == generation && current.ownerId().equals(owner)) {
                removed[0] = true;
                return null;
            }
            return current;
        });
        return removed[0];
    }

    /**
     * Removes the owner's state lease when its generation is not newer than the
     * STOPPED event. This lets a stop transition use a fresh fencing generation,
     * while delayed stop events cannot revoke a later transition or new owner.
     */
    public boolean revokeAtOrBeforeGeneration(
            String tenantId, String agentId, String ownerId, long generation) {
        Key key = new Key(requireText(tenantId, "tenantId"), requireText(agentId, "agentId"));
        String owner = requireText(ownerId, "ownerId");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        final boolean[] removed = {false};
        leases.computeIfPresent(key, (ignored, current) -> {
            if (current.generation() <= generation && current.ownerId().equals(owner)) {
                removed[0] = true;
                return null;
            }
            return current;
        });
        return removed[0];
    }

    /**
     * Compatibility revocation for legacy single-process callers. Fenced hosts
     * must use the owner/generation overload so an old host cannot revoke a successor.
     */
    public boolean revoke(String tenantId, String agentId) {
        Key key = new Key(requireText(tenantId, "tenantId"), requireText(agentId, "agentId"));
        AgentRuntimeStateLease current = leases.get(key);
        if (current == null || !current.ownerId().equals("legacy")) return false;
        return leases.remove(key, current);
    }

    private void validateLease(AgentRuntimeStateLease lease) {
        if (lease == null) throw new IllegalArgumentException("lease must not be null");
        if (!lease.expiresAt().isAfter(clock.instant()))
            throw new IllegalArgumentException("lease expiry must be in the future");
    }

    @Override
    public Optional<AgentRuntimeState> resolve(
            String agentId, CoordinationRequest request, CoordinationContext context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        if (agentId == null || agentId.isBlank()
                || context.tenantId() == null || context.tenantId().isBlank()) return Optional.empty();

        Key key = new Key(context.tenantId().trim(), agentId.trim());
        AgentRuntimeStateLease lease = leases.get(key);
        if (lease == null) return Optional.empty();

        Instant now = clock.instant();
        if (!lease.expiresAt().isAfter(now)) {
            leases.remove(key, lease);
            return Optional.empty();
        }
        return Optional.of(lease.state());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private record Key(String tenantId, String agentId) {}
}
