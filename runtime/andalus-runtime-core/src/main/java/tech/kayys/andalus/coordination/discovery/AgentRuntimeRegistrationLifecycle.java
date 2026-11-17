package tech.kayys.andalus.coordination.discovery;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.andalus.agent.capability.AgentRuntimeLease;
import tech.kayys.andalus.agent.capability.AgentRuntimeLeaseToken;
import tech.kayys.andalus.agent.capability.AgentRuntimeRegistrationRequest;
import tech.kayys.andalus.agent.capability.AgentRuntimeRegistrationStore;
import tech.kayys.andalus.agent.capability.AgentRuntimeStatus;

/**
 * Runtime-facing lifecycle facade for shared agent registration leases.
 *
 * <p>The host calls {@link #start} during startup, {@link #markAvailable}
 * after readiness checks, {@link #heartbeat} on a managed heartbeat schedule,
 * {@link #markUnavailable} when the instance should leave routing, and
 * {@link #shutdown} during graceful shutdown. This class deliberately creates
 * no background threads or timers: scheduling and shutdown ordering belong to
 * the hosting runtime.</p>
 *
 * <p>Registration tokens are independent of execution/task lease tokens.
 * Callers must retain and present the exact token returned by start; the
 * registration store performs authoritative expiry and fencing checks.</p>
 */
@ApplicationScoped
public class AgentRuntimeRegistrationLifecycle {

    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(30);

    private final AgentRuntimeRegistrationStore registrations;
    private final Duration leaseDuration;

    @Inject
    public AgentRuntimeRegistrationLifecycle(AgentRuntimeRegistrationStore registrations) {
        this(registrations, DEFAULT_LEASE_DURATION);
    }

    AgentRuntimeRegistrationLifecycle(
            AgentRuntimeRegistrationStore registrations, Duration leaseDuration) {
        this.registrations = Objects.requireNonNull(registrations, "registrations must not be null");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }

    /** Lease duration used by registrations created through this facade. */
    public Duration leaseDuration() {
        return leaseDuration;
    }

    /** Acquires a registration in STARTING state; empty means another live owner holds it. */
    public Optional<AgentRuntimeLease> start(
            String tenantId, String agentId, String instanceId, String ownerId) {
        return registrations.acquire(new AgentRuntimeRegistrationRequest(
                tenantId, agentId, instanceId, ownerId, leaseDuration));
    }

    /** Publishes readiness and atomically renews the registration lease. */
    public Optional<AgentRuntimeLease> markAvailable(AgentRuntimeLeaseToken token) {
        return publish(token, AgentRuntimeStatus.AVAILABLE);
    }

    /** Removes this instance from routing while retaining its registration lease. */
    public Optional<AgentRuntimeLease> markUnavailable(AgentRuntimeLeaseToken token) {
        return publish(token, AgentRuntimeStatus.UNAVAILABLE);
    }

    /** Renews the current state without changing ownership or generation. */
    public Optional<AgentRuntimeLease> heartbeat(AgentRuntimeLeaseToken token) {
        return registrations.renew(Objects.requireNonNull(token, "token must not be null"), leaseDuration);
    }

    /** Fenced graceful shutdown; stale tokens cannot revoke a successor. */
    public boolean shutdown(AgentRuntimeLeaseToken token) {
        return registrations.revoke(Objects.requireNonNull(token, "token must not be null"));
    }

    private Optional<AgentRuntimeLease> publish(
            AgentRuntimeLeaseToken token, AgentRuntimeStatus status) {
        return registrations.publishStatus(
                Objects.requireNonNull(token, "token must not be null"), status, leaseDuration);
    }
}
