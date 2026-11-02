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
 * Multi-instance deployments should supply a shared implementation of the SPI.
 */
@ApplicationScoped
public class InMemoryAgentRuntimeStateProvider implements AgentRuntimeStateProvider {
    private final Map<Key, AgentRuntimeStateLease> leases = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryAgentRuntimeStateProvider() { this(Clock.systemUTC()); }

    InMemoryAgentRuntimeStateProvider(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Publishes or renews an agent's state lease for one tenant. */
    public void publish(AgentRuntimeStateLease lease) {
        if (lease == null) throw new IllegalArgumentException("lease must not be null");
        if (!lease.expiresAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("lease expiry must be in the future");
        }
        leases.put(new Key(lease.tenantId(), lease.agentId()), lease);
    }

    /** Removes one tenant's lease for an agent. */
    public boolean revoke(String tenantId, String agentId) {
        return leases.remove(new Key(requireText(tenantId, "tenantId"),
                requireText(agentId, "agentId"))) != null;
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
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private record Key(String tenantId, String agentId) {}
}
