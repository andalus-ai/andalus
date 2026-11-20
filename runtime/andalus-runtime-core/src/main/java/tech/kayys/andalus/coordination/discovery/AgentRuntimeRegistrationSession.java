package tech.kayys.andalus.coordination.discovery;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import tech.kayys.andalus.agent.capability.AgentRuntimeLease;
import tech.kayys.andalus.agent.capability.AgentRuntimeLeaseToken;

/** Host-owned, fail-closed session for a distributed runtime registration. */
public final class AgentRuntimeRegistrationSession implements AutoCloseable {
    private final AgentRuntimeRegistrationLifecycle lifecycle;
    private final AgentRuntimeLeaseToken token;
    private final AgentRuntimeRegistrationObserver observer;
    private volatile ScheduledFuture<?> heartbeat;
    private volatile boolean routable;
    private boolean ownershipLost;
    private boolean closed;

    private AgentRuntimeRegistrationSession(AgentRuntimeRegistrationLifecycle lifecycle,
            AgentRuntimeLeaseToken token, AgentRuntimeRegistrationObserver observer) {
        this.lifecycle = lifecycle;
        this.token = token;
        this.observer = observer;
    }

    /** Acquires STARTING and schedules renewal on the caller-owned scheduler. */
    public static Optional<AgentRuntimeRegistrationSession> open(
            AgentRuntimeRegistrationLifecycle lifecycle, ScheduledExecutorService hostScheduler,
            String tenantId, String agentId, String instanceId, String ownerId,
            Duration heartbeatInterval) {
        return open(lifecycle, hostScheduler, tenantId, agentId, instanceId, ownerId,
                heartbeatInterval, AgentRuntimeRegistrationObserver.NOOP);
    }

    /** Opens a session with an optional runtime-owned telemetry observer. */
    public static Optional<AgentRuntimeRegistrationSession> open(
            AgentRuntimeRegistrationLifecycle lifecycle, ScheduledExecutorService hostScheduler,
            String tenantId, String agentId, String instanceId, String ownerId,
            Duration heartbeatInterval, AgentRuntimeRegistrationObserver observer) {
        Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        Objects.requireNonNull(hostScheduler, "hostScheduler must not be null");
        Objects.requireNonNull(heartbeatInterval, "heartbeatInterval must not be null");
        Objects.requireNonNull(observer, "observer must not be null");
        Duration leaseDuration = lifecycle.leaseDuration();
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()
                || heartbeatInterval.toMillis() < 1
                || heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException(
                    "heartbeatInterval must be positive and shorter than the lease duration");
        }
        Optional<AgentRuntimeLease> acquired =
                lifecycle.start(tenantId, agentId, instanceId, ownerId);
        if (acquired.isEmpty()) {
            return Optional.empty();
        }
        AgentRuntimeRegistrationSession session = new AgentRuntimeRegistrationSession(
                lifecycle, acquired.get().token(), observer);
        session.emit(AgentRuntimeRegistrationObserver.Event.ACQUIRED);
        try {
            session.heartbeat = hostScheduler.scheduleWithFixedDelay(session::renew,
                    heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
            return Optional.of(session);
        } catch (RuntimeException schedulingFailure) {
            session.emit(AgentRuntimeRegistrationObserver.Event.SCHEDULING_ERROR);
            lifecycle.shutdown(session.token);
            throw schedulingFailure;
        }
    }

    public synchronized boolean markAvailable() {
        if (closed || ownershipLost) return false;
        try {
            boolean published = lifecycle.markAvailable(token).isPresent();
            routable = published;
            emit(published ? AgentRuntimeRegistrationObserver.Event.AVAILABLE
                    : AgentRuntimeRegistrationObserver.Event.STATUS_REJECTED);
            if (!published) loseOwnership();
            return published;
        } catch (RuntimeException failure) {
            emit(AgentRuntimeRegistrationObserver.Event.OPERATION_ERROR);
            loseOwnership();
            throw failure;
        }
    }

    public synchronized boolean markUnavailable() {
        if (closed || ownershipLost) {
            routable = false;
            return false;
        }
        try {
            boolean published = lifecycle.markUnavailable(token).isPresent();
            routable = false;
            emit(published ? AgentRuntimeRegistrationObserver.Event.UNAVAILABLE
                    : AgentRuntimeRegistrationObserver.Event.RENEWAL_REJECTED);
            if (!published) loseOwnership();
            return published;
        } catch (RuntimeException failure) {
            routable = false;
            emit(AgentRuntimeRegistrationObserver.Event.OPERATION_ERROR);
            loseOwnership();
            throw failure;
        }
    }

    /** Local readiness view; it becomes false immediately when renewal is unproven. */
    public boolean isRoutable() { return routable; }

    public AgentRuntimeLeaseToken token() { return token; }

    private synchronized void renew() {
        if (closed || ownershipLost) return;
        long started = System.nanoTime();
        try {
            if (lifecycle.heartbeat(token).isEmpty()) {
                routable = false;
                emit(AgentRuntimeRegistrationObserver.Event.RENEWAL_REJECTED);
                loseOwnership();
            } else {
                emit(AgentRuntimeRegistrationObserver.Event.RENEWED);
            }
        } catch (RuntimeException failure) {
            routable = false;
            emit(AgentRuntimeRegistrationObserver.Event.RENEWAL_ERROR);
            loseOwnership();
        } finally {
            try {
                observer.onRenewalDuration(System.nanoTime() - started);
            } catch (RuntimeException ignored) {
                // Instrumentation must never affect lease ownership or routing safety.
            }
        }
    }

    private void emit(AgentRuntimeRegistrationObserver.Event event) {
        try {
            observer.onEvent(event);
        } catch (RuntimeException ignored) {
            // Instrumentation must never affect ownership or routing safety.
        }
    }

    private void loseOwnership() {
        ownershipLost = true;
        ScheduledFuture<?> task = heartbeat;
        if (task != null) task.cancel(false);
    }

    /** Cancels heartbeats before fenced revocation; stale tokens cannot revoke successors. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        routable = false;
        heartbeat.cancel(false);
        if (!ownershipLost) {
            try {
                emit(lifecycle.shutdown(token)
                        ? AgentRuntimeRegistrationObserver.Event.REVOKED
                        : AgentRuntimeRegistrationObserver.Event.REVOCATION_REJECTED);
            } catch (RuntimeException failure) {
                emit(AgentRuntimeRegistrationObserver.Event.OPERATION_ERROR);
                throw failure;
            }
        }
    }
}
