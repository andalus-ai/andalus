package tech.kayys.andalus.coordination.discovery;

import java.time.Duration;
import java.util.Objects;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import tech.kayys.andalus.execution.mobility.ExecutionLease;
import tech.kayys.andalus.execution.mobility.FencedLeaseManager;

/**
 * Allocates authoritative fencing generations before publishing agent lifecycle events.
 *
 * <p>The lease manager is the source of truth for ownership and generation. This class
 * deliberately fails closed when no unique lease manager is available or when another
 * owner holds the lease. State transitions acquire a new generation; explicit healthy
 * heartbeats renew the current token. STOPPED is published under a fresh generation and then
 * releases that exact token.</p>
 *
 * <p>In multi-instance deployments the configured {@link FencedLeaseManager} must use
 * an atomic shared coordinator. A process-local implementation is suitable only for
 * single-runtime deployments.</p>
 */
@ApplicationScoped
public class AgentRuntimeLifecycleCoordinator {

    private static final Duration DEFAULT_LEASE_TTL = Duration.ofSeconds(30);

    private final Instance<FencedLeaseManager> leaseManagers;
    private final Event<AgentRuntimeLifecycleEvent> lifecycleEvents;
    private final Duration leaseTtl;

    @Inject
    public AgentRuntimeLifecycleCoordinator(
            Instance<FencedLeaseManager> leaseManagers,
            Event<AgentRuntimeLifecycleEvent> lifecycleEvents) {
        this(leaseManagers, lifecycleEvents, DEFAULT_LEASE_TTL);
    }

    AgentRuntimeLifecycleCoordinator(
            Instance<FencedLeaseManager> leaseManagers,
            Event<AgentRuntimeLifecycleEvent> lifecycleEvents,
            Duration leaseTtl) {
        this.leaseManagers = Objects.requireNonNull(leaseManagers, "leaseManagers must not be null");
        this.lifecycleEvents = Objects.requireNonNull(lifecycleEvents, "lifecycleEvents must not be null");
        this.leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl must not be null");
        if (leaseTtl.isZero() || leaseTtl.isNegative()) {
            throw new IllegalArgumentException("leaseTtl must be positive");
        }
    }

    /**
     * Publishes a lifecycle transition only after obtaining its fencing generation.
     * Each transition, including entry into HEALTHY, obtains a new generation.
     *
     * @throws IllegalStateException if the lease manager is missing/ambiguous or another
     *                               owner currently holds the agent lease
     */
    public AgentRuntimeLifecycleEvent transition(
            String agentId,
            String tenantId,
            String ownerId,
            AgentRuntimeLifecycleEvent.Type type,
            int priority,
            double costFactor) {
        String agent = requireText(agentId, "agentId");
        String tenant = requireText(tenantId, "tenantId");
        String owner = requireText(ownerId, "ownerId");
        Objects.requireNonNull(type, "type must not be null");

        FencedLeaseManager leaseManager = resolveLeaseManager();
        String leaseKey = leaseKey(tenant, agent);
        ExecutionLease lease = leaseManager.acquireLease(leaseKey, owner, leaseTtl);

        AgentRuntimeLifecycleEvent event = new AgentRuntimeLifecycleEvent(
                agent, tenant, owner, lease.fencingToken().generation(),
                type, priority, costFactor);

        if (type == AgentRuntimeLifecycleEvent.Type.STOPPED) {
            try {
                lifecycleEvents.fire(event);
            } finally {
                leaseManager.releaseLease(leaseKey, owner, lease.fencingToken());
            }
        } else {
            lifecycleEvents.fire(event);
        }
        return event;
    }

    /**
     * Renews a healthy agent's current token without advancing its generation.
     * Call this only for a heartbeat, not for a state transition into HEALTHY.
     */
    public AgentRuntimeLifecycleEvent renewHealthy(
            String agentId, String tenantId, String ownerId, int priority, double costFactor) {
        String agent = requireText(agentId, "agentId");
        String tenant = requireText(tenantId, "tenantId");
        String owner = requireText(ownerId, "ownerId");
        FencedLeaseManager leaseManager = resolveLeaseManager();
        String leaseKey = leaseKey(tenant, agent);
        ExecutionLease current = leaseManager.getLease(leaseKey)
                .orElseThrow(() -> new IllegalStateException("No active agent lease to renew"));
        if (!current.holderId().equals(owner)) {
            throw new IllegalStateException("Agent lease is held by another runtime owner");
        }
        if (!leaseManager.renewLease(leaseKey, owner, current.fencingToken(), leaseTtl)) {
            throw new IllegalStateException("Agent lease renewal was rejected; ownership may have changed");
        }
        AgentRuntimeLifecycleEvent event = new AgentRuntimeLifecycleEvent(
                agent, tenant, owner, current.fencingToken().generation(),
                AgentRuntimeLifecycleEvent.Type.HEALTHY, priority, costFactor);
        lifecycleEvents.fire(event);
        return event;
    }

    private FencedLeaseManager resolveLeaseManager() {
        if (leaseManagers.isUnsatisfied() || leaseManagers.isAmbiguous()) {
            throw new IllegalStateException(
                    "Exactly one FencedLeaseManager must be configured to publish agent lifecycle events");
        }
        return leaseManagers.get();
    }

    /**
     * Length-prefixing prevents collisions between different tenant/agent pairs.
     */
    static String leaseKey(String tenantId, String agentId) {
        return tenantId.length() + ":" + tenantId + agentId.length() + ":" + agentId;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
