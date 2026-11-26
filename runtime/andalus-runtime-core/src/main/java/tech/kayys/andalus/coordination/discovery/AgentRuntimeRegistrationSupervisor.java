package tech.kayys.andalus.coordination.discovery;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import tech.kayys.andalus.agent.capability.AgentRuntimeLeaseToken;

/**
 * Host-invoked supervisor that reacquires registration after ownership loss.
 *
 * <p>The scheduler is supplied and owned by the host. Recovery always calls
 * registration acquire again, obtaining a new generation; it never retries
 * heartbeat or status publication with a token whose ownership was lost.
 * The supervisor restores AVAILABLE only if the host previously requested it.</p>
 */
public final class AgentRuntimeRegistrationSupervisor implements AutoCloseable {

    private final AgentRuntimeRegistrationLifecycle lifecycle;
    private final ScheduledExecutorService hostScheduler;
    private final AgentRuntimeRegistrationReadiness readiness;
    private final AgentRuntimeRegistrationObserver downstreamObserver;
    private final String tenantId;
    private final String agentId;
    private final String instanceId;
    private final String ownerId;
    private final Duration heartbeatInterval;
    private final long initialRetryMillis;
    private final long maxRetryMillis;
    private final AtomicInteger recoveryAttempt = new AtomicInteger();
    private final Object recoveryLock = new Object();

    private volatile AgentRuntimeRegistrationSession session;
    private volatile ScheduledFuture<?> recoveryTask;
    private volatile boolean desiredAvailable;
    private volatile boolean ownershipLost;
    private volatile boolean closed;

    public AgentRuntimeRegistrationSupervisor(
            AgentRuntimeRegistrationLifecycle lifecycle,
            ScheduledExecutorService hostScheduler,
            AgentRuntimeRegistrationReadiness readiness,
            String tenantId,
            String agentId,
            String instanceId,
            String ownerId,
            Duration heartbeatInterval,
            Duration initialRetryDelay,
            Duration maxRetryDelay) {
        this(lifecycle, hostScheduler, readiness, tenantId, agentId, instanceId, ownerId,
                heartbeatInterval, initialRetryDelay, maxRetryDelay,
                AgentRuntimeRegistrationObserver.NOOP);
    }

    public AgentRuntimeRegistrationSupervisor(
            AgentRuntimeRegistrationLifecycle lifecycle,
            ScheduledExecutorService hostScheduler,
            AgentRuntimeRegistrationReadiness readiness,
            String tenantId,
            String agentId,
            String instanceId,
            String ownerId,
            Duration heartbeatInterval,
            Duration initialRetryDelay,
            Duration maxRetryDelay,
            AgentRuntimeRegistrationObserver downstreamObserver) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.hostScheduler = Objects.requireNonNull(hostScheduler, "hostScheduler must not be null");
        this.readiness = Objects.requireNonNull(readiness, "readiness must not be null");
        this.tenantId = requireText(tenantId, "tenantId");
        this.agentId = requireText(agentId, "agentId");
        this.instanceId = requireText(instanceId, "instanceId");
        this.ownerId = requireText(ownerId, "ownerId");
        this.heartbeatInterval = positive(heartbeatInterval, "heartbeatInterval");
        this.initialRetryMillis = positive(initialRetryDelay, "initialRetryDelay").toMillis();
        this.maxRetryMillis = positive(maxRetryDelay, "maxRetryDelay").toMillis();
        if (heartbeatInterval.compareTo(lifecycle.leaseDuration()) >= 0) {
            throw new IllegalArgumentException("heartbeatInterval must be shorter than the registration lease");
        }
        if (maxRetryMillis < initialRetryMillis) {
            throw new IllegalArgumentException("maxRetryDelay must be greater than or equal to initialRetryDelay");
        }
        this.downstreamObserver = Objects.requireNonNull(downstreamObserver, "downstreamObserver must not be null");
    }

    /** Begins registration. Returns true when a STARTING registration was acquired. */
    public synchronized boolean start() {
        if (closed) throw new IllegalStateException("supervisor is closed");
        if (session != null && !ownershipLost) return true;
        readiness.starting();
        return acquireAndRestore();
    }

    /**
     * Reconciles registration after the host has restored its scheduler or persistence path.
     *
     * <p>A healthy session is left untouched. If ownership was lost, reconciliation
     * performs a fresh acquire and therefore obtains a new fenced generation. This
     * operation is serialized with scheduled recovery, start, and close.</p>
     *
     * @return true when a current registration exists (STARTING if availability was
     *         not requested, READY when desired availability was restored)
     */
    public synchronized boolean reconcile() {
        emit(AgentRuntimeRegistrationObserver.Event.RECONCILIATION_ATTEMPTED);
        if (closed) {
            emit(AgentRuntimeRegistrationObserver.Event.RECONCILIATION_FAILED);
            return false;
        }
        AgentRuntimeRegistrationSession current = session;
        if (current != null && !ownershipLost
                && (!desiredAvailable || current.isRoutable())) {
            emit(AgentRuntimeRegistrationObserver.Event.RECONCILIATION_SUCCEEDED);
            return true;
        }
        readiness.starting();
        boolean reconciled = acquireAndRestore();
        emit(reconciled ? AgentRuntimeRegistrationObserver.Event.RECONCILIATION_SUCCEEDED
                : AgentRuntimeRegistrationObserver.Event.RECONCILIATION_FAILED);
        return reconciled;
    }

    /** Publishes AVAILABLE after host-local startup checks have succeeded. */
    public synchronized boolean markAvailable() {
        if (closed) return false;
        desiredAvailable = true;
        AgentRuntimeRegistrationSession current = session;
        if (current == null) {
            readiness.degraded();
            scheduleRecovery();
            return false;
        }
        boolean available = current.markAvailable();
        if (available) {
            readiness.ready();
        } else {
            readiness.degraded();
        }
        return available;
    }

    /** Publishes UNAVAILABLE while retaining the lease for a later readiness transition. */
    public synchronized boolean markUnavailable() {
        if (closed) return false;
        desiredAvailable = false;
        AgentRuntimeRegistrationSession current = session;
        if (current == null) {
            readiness.degraded();
            scheduleRecovery();
            return false;
        }
        boolean unavailable = current.markUnavailable();
        readiness.degraded();
        return unavailable;
    }

    public boolean isReady() {
        return readiness.isReady();
    }

    public Optional<AgentRuntimeLeaseToken> token() {
        AgentRuntimeRegistrationSession current = session;
        return current == null || ownershipLost ? Optional.empty() : Optional.of(current.token());
    }

    private boolean acquireAndRestore() {
        cancelRecovery();
        AgentRuntimeRegistrationSession previous = session;
        session = null;
        ownershipLost = true;
        if (previous != null) {
            try {
                previous.close();
            } catch (RuntimeException ignored) {
                // The new acquisition is fenced by generation; cleanup is best effort.
            }
        }

        try {
            Optional<AgentRuntimeRegistrationSession> acquired = AgentRuntimeRegistrationSession.open(
                    lifecycle, hostScheduler, tenantId, agentId, instanceId, ownerId,
                    heartbeatInterval, sessionObserver());
            if (acquired.isEmpty()) {
                readiness.degraded();
                scheduleRecovery();
                return false;
            }

            AgentRuntimeRegistrationSession current = acquired.get();
            session = current;
            ownershipLost = false;
            if (desiredAvailable) {
                if (current.markAvailable()) {
                    readiness.ready();
                    recoveryAttempt.set(0);
                    return true;
                }
                readiness.degraded();
                current.close();
                session = null;
                scheduleRecovery();
                return false;
            }

            readiness.starting();
            recoveryAttempt.set(0);
            return true;
        } catch (RuntimeException registrationFailure) {
            readiness.degraded();
            scheduleRecovery();
            return false;
        }
    }

    private AgentRuntimeRegistrationObserver sessionObserver() {
        return new AgentRuntimeRegistrationObserver() {
            @Override
            public void onEvent(Event event) {
                onSessionEvent(event);
            }

            @Override
            public void onRenewalDuration(long elapsedNanos) {
                try {
                    downstreamObserver.onRenewalDuration(elapsedNanos);
                } catch (RuntimeException ignored) {
                    // Host telemetry must not affect registration ownership.
                }
            }
        };
    }

    private void onSessionEvent(AgentRuntimeRegistrationObserver.Event event) {
        try {
            downstreamObserver.onEvent(event);
        } catch (RuntimeException ignored) {
            // Host telemetry must not affect registration ownership.
        }

        if (event == AgentRuntimeRegistrationObserver.Event.ACQUIRED) {
            ownershipLost = false;
        }
        if (event == AgentRuntimeRegistrationObserver.Event.RENEWAL_REJECTED
                || event == AgentRuntimeRegistrationObserver.Event.RENEWAL_ERROR
                || event == AgentRuntimeRegistrationObserver.Event.STATUS_REJECTED
                || event == AgentRuntimeRegistrationObserver.Event.OPERATION_ERROR) {
            ownershipLost = true;
            readiness.degraded();
            scheduleRecovery();
        }
    }

    private void emit(AgentRuntimeRegistrationObserver.Event event) {
        try {
            downstreamObserver.onEvent(event);
        } catch (RuntimeException ignored) {
            // Host telemetry must not affect registration ownership.
        }
    }

    private void scheduleRecovery() {
        synchronized (recoveryLock) {
            if (closed || (recoveryTask != null && !recoveryTask.isDone())) return;
            long delay = nextRetryDelayMillis();
            try {
                recoveryTask = hostScheduler.schedule(this::recover, delay, TimeUnit.MILLISECONDS);
            } catch (RuntimeException schedulerUnavailable) {
                readiness.degraded();
                try {
                    downstreamObserver.onEvent(
                            AgentRuntimeRegistrationObserver.Event.RECOVERY_SCHEDULING_REJECTED);
                } catch (RuntimeException ignored) {
                    // Telemetry must not affect fail-closed recovery state.
                }
            }
        }
    }

    private void recover() {
        synchronized (this) {
            synchronized (recoveryLock) {
                recoveryTask = null;
            }
            if (closed) return;
            emit(AgentRuntimeRegistrationObserver.Event.RECOVERY_ATTEMPTED);
            boolean recovered = acquireAndRestore();
            emit(recovered ? AgentRuntimeRegistrationObserver.Event.RECOVERY_SUCCEEDED
                    : AgentRuntimeRegistrationObserver.Event.RECOVERY_FAILED);
        }
    }

    private long nextRetryDelayMillis() {
        int attempt = recoveryAttempt.getAndIncrement();
        int shift = Math.min(attempt, 30);
        long multiplier = 1L << shift;
        if (initialRetryMillis > maxRetryMillis / multiplier) return maxRetryMillis;
        return Math.min(maxRetryMillis, initialRetryMillis * multiplier);
    }

    private void cancelRecovery() {
        synchronized (recoveryLock) {
            ScheduledFuture<?> task = recoveryTask;
            recoveryTask = null;
            if (task != null) task.cancel(false);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        desiredAvailable = false;
        ownershipLost = true;
        cancelRecovery();
        AgentRuntimeRegistrationSession current = session;
        session = null;
        try {
            if (current != null) current.close();
        } finally {
            readiness.stopped();
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative() || value.toMillis() < 1) {
            throw new IllegalArgumentException(name + " must be at least one millisecond");
        }
        return value;
    }
}
