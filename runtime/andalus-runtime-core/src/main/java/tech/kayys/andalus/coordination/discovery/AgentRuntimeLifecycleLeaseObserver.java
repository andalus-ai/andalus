package tech.kayys.andalus.coordination.discovery;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Converts host lifecycle events into short-lived routing-state leases.
 * Hosts must renew by emitting HEALTHY periodically; missing renewals expire.
 */
@ApplicationScoped
public class AgentRuntimeLifecycleLeaseObserver {
    private final InMemoryAgentRuntimeStateProvider stateProvider;
    private final Clock clock;
    private final Duration leaseTtl;

    @Inject
    public AgentRuntimeLifecycleLeaseObserver(InMemoryAgentRuntimeStateProvider stateProvider) {
        this(stateProvider, Clock.systemUTC(), Duration.ofSeconds(30));
    }

    AgentRuntimeLifecycleLeaseObserver(
            InMemoryAgentRuntimeStateProvider stateProvider, Clock clock, Duration leaseTtl) {
        this.stateProvider = Objects.requireNonNull(stateProvider, "stateProvider must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl must not be null");
        if (leaseTtl.isZero() || leaseTtl.isNegative())
            throw new IllegalArgumentException("leaseTtl must be positive");
    }

    public void onLifecycleEvent(@Observes AgentRuntimeLifecycleEvent event) {
        if (event == null) throw new IllegalArgumentException("event must not be null");
        switch (event.type()) {
            case STARTED, UNHEALTHY -> publishUnavailable(event);
            case HEALTHY -> stateProvider.publish(new AgentRuntimeStateLease(
                    event.agentId(), event.tenantId(),
                    new AgentRuntimeState(true, event.priority(), event.costFactor()),
                    clock.instant().plus(leaseTtl)));
            case STOPPED -> stateProvider.revoke(event.tenantId(), event.agentId());
        }
    }

    private void publishUnavailable(AgentRuntimeLifecycleEvent event) {
        stateProvider.publish(new AgentRuntimeStateLease(
                event.agentId(), event.tenantId(),
                new AgentRuntimeState(false, event.priority(), event.costFactor()),
                clock.instant().plus(leaseTtl)));
    }
}
