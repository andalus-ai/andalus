package tech.kayys.andalus.coordination.discovery;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import tech.kayys.andalus.agent.capability.AgentRuntimeLease;
import tech.kayys.andalus.agent.capability.AgentRuntimeLeaseToken;

/**
 * Host-owned registration session.
 *
 * <p>The host supplies its managed scheduler; this class never creates threads or
 * owns the scheduler lifecycle. It retains the exact registration token, renews
 * it at a bounded cadence, and fails local readiness closed if renewal is lost.
 * Closing the session cancels heartbeats before attempting fenced revocation.</p>
 */
public final class AgentRuntimeRegistrationSession implements AutoCloseable {

    private final AgentRuntimeRegistrationLifecycle lifecycle;
    private final AgentRuntimeLeaseToken token;
    private volatile ScheduledFuture<?> heartbeat;
    private volatile boolean routable;
    private boolean ownershipLost;
    private boolean closed;

    private AgentRuntimeRegistrationSession(
            AgentRuntimeRegistrationLifecycle lifecycle,
            AgentRuntimeLeaseToken token) {
        this.lifecycle = lifecycle;
        this.token = token;
    }

    /**
     * Acquires a STARTING registration and schedules renewal on the host scheduler.
     * Empty means another live owner holds the requested instance identity.
     */
    public static Optional<AgentRuntimeRegistrationSession> open(
            AgentRuntimeRegistrationLifecycle lifecycle,
            ScheduledExecutorService hostScheduler,
            String tenantId,
            String agentId,
            String instanceId,
            String ownerId,
            Duration heartbeatInterval) {
        Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        Objects.requireNonNull(hostScheduler, "hostScheduler must not be null");
        Objects.requireNonNull(heartbeatInterval, "heartbeatInterval must not be null");
        Duration leaseDuration = lifecycle.leaseDuration();
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()
                || heartbeatInterval.toMillis() < 1
                || heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("heartbeatInterval must be positive and shorter than the lease duration");
        }

        Optional<AgentRuntimeLease> acquired =
                lifecycle.start(tenantId, agentId, instanceId, ownerId);
        if (acquired.isEmpty()) {
            return Optional.empty();
        }

        AgentRuntimeLeaseToken token = acquired.get().token();
        AgentRuntimeRegistrationSession session =
                new AgentRuntimeRegistrationSession(lifecycle, token);
        try {
            session.heartbeat = hostScheduler.scheduleWithFixedDelay(
                    session::renew, heartbeatInterval.toMillis(),
                    heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
            return Optional.of(session);
        } catch (RuntimeException schedulingFailure) {
            lifecycle.shutdown(token);
            throw schedulingFailure;
        }
    }

    /** Publishes AVAILABLE only while this session still owns a live registration. */
    public synchronized boolean markAvailable() {
        if (closed || ownershipLost) {
            return false;
        }
        boolean published = lifecycle.markAvailable(token).isPresent();
        routable = published;
        if (!published) {
            ownershipLost = true;
            heartbeat.cancel(false);
        }
        return published;
    }

    /** Removes the instance from routing without relinquishing its registration. */
    public synchronized boolean markUnavailable() {
        if (closed || ownershipLost) {
            routable = false;
            return false;
        }
        boolean published = lifecycle.markUnavailable(token).isPresent();
        routable = false;
        if (!published) {
            ownershipLost = true;
            heartbeat.cancel(false);
        }
        return published;
    }

    /** Local routing/readiness view; false immediately after a failed renewal. */
    public boolean isRoutable() {
        return routable;
    }

    public AgentRuntimeLeaseToken token() {
        return token;
    }

    private synchronized void renew() {
        if (closed || ownershipLost) {
            return;
        }
        if (lifecycle.heartbeat(token).isEmpty()) {
            routable = false;
            ownershipLost = true;
            heartbeat.cancel(false);
        }
    }

    /**
     * Stops the scheduled heartbeat first, then revokes this exact token.
     * A lost/stale token cannot revoke a successor.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        routable = false;
        heartbeat.cancel(false);
        if (!ownershipLost) {
            lifecycle.shutdown(token);
        }
    }
}
