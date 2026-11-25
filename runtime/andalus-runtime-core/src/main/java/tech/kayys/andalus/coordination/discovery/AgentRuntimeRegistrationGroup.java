package tech.kayys.andalus.coordination.discovery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

import tech.kayys.andalus.agent.capability.AgentRuntimeLeaseToken;

/**
 * Owns independent registration supervisors for the logical agents hosted by one process.
 *
 * <p>Each registration receives its own readiness state and fenced lease session. The
 * group shares only the host-owned scheduler and lifecycle facade. It creates no
 * threads and never infers tenant or agent identity.</p>
 *
 * <p>The group monitor protects membership only. Supervisor operations may perform
 * database or scheduler work and are therefore always invoked outside that monitor.
 * Each supervisor remains responsible for serializing its own lifecycle transitions.</p>
 */
public final class AgentRuntimeRegistrationGroup implements AutoCloseable {

    private final AgentRuntimeRegistrationLifecycle lifecycle;
    private final ScheduledExecutorService scheduler;
    private final AgentRuntimeRegistrationObserver observer;
    private final Map<Key, Entry> entries = new LinkedHashMap<>();
    private boolean closed;

    public AgentRuntimeRegistrationGroup(
            AgentRuntimeRegistrationLifecycle lifecycle,
            ScheduledExecutorService scheduler) {
        this(lifecycle, scheduler, AgentRuntimeRegistrationObserver.NOOP);
    }

    public AgentRuntimeRegistrationGroup(
            AgentRuntimeRegistrationLifecycle lifecycle,
            ScheduledExecutorService scheduler,
            AgentRuntimeRegistrationObserver observer) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
    }

    /**
     * Adds and starts one independently supervised agent registration.
     * Duplicate tenant/agent/instance identities are rejected.
     *
     * <p>Membership is reserved before startup so concurrent duplicate registrations
     * cannot race. Startup runs outside the group monitor. If shutdown wins that race,
     * the newly started supervisor is closed and this method fails.</p>
     */
    public Key register(AgentRuntimeRegistrationHost.Registration registration) {
        Objects.requireNonNull(registration, "registration must not be null");
        Key key = new Key(registration.tenantId(), registration.agentId(), registration.instanceId());
        AgentRuntimeRegistrationReadiness readiness = new AgentRuntimeRegistrationReadiness();
        AgentRuntimeRegistrationSupervisor supervisor = new AgentRuntimeRegistrationSupervisor(
                lifecycle, scheduler, readiness,
                registration.tenantId(), registration.agentId(), registration.instanceId(),
                registration.ownerId(), registration.heartbeatInterval(),
                registration.initialRetryDelay(), registration.maxRetryDelay(), observer);
        Entry entry = new Entry(supervisor, readiness);

        synchronized (this) {
            ensureOpen();
            if (entries.containsKey(key)) {
                throw new IllegalStateException("Agent registration already exists: " + key);
            }
            entries.put(key, entry);
        }

        try {
            supervisor.start();
            synchronized (this) {
                if (!closed && entries.get(key) == entry) {
                    return key;
                }
            }
            closeAfterFailedRegistration(supervisor);
            throw new IllegalStateException("registration group closed during agent startup: " + key);
        } catch (RuntimeException failure) {
            synchronized (this) {
                entries.remove(key, entry);
            }
            try {
                supervisor.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** Publishes AVAILABLE only for the selected logical agent. */
    public boolean markAvailable(Key key) {
        Entry entry = find(Objects.requireNonNull(key, "key must not be null"));
        return entry != null && entry.supervisor().markAvailable();
    }

    /** Withdraws only the selected logical agent while retaining its lease for recovery. */
    public boolean markUnavailable(Key key) {
        Entry entry = find(Objects.requireNonNull(key, "key must not be null"));
        return entry != null && entry.supervisor().markUnavailable();
    }

    /** Removes one registration and performs fenced shutdown outside the group monitor. */
    public boolean unregister(Key key) {
        Entry entry;
        synchronized (this) {
            entry = entries.remove(Objects.requireNonNull(key, "key must not be null"));
        }
        if (entry == null) return false;
        entry.supervisor().close();
        return true;
    }

    /**
     * Reconciles one selected registration without blocking group membership operations.
     * A detached or unknown registration returns false.
     */
    public boolean reconcile(Key key) {
        Entry entry = find(Objects.requireNonNull(key, "key must not be null"));
        return entry != null && entry.supervisor().reconcile();
    }

    /**
     * Reconciles a snapshot of all registrations independently.
     *
     * <p>Each supervisor serializes its own reconciliation with heartbeat recovery
     * and shutdown. Failures for one agent do not prevent attempts for its siblings.
     * Entries removed after the snapshot may return false because their supervisor
     * has already been closed.</p>
     *
     * @return immutable per-registration reconciliation results
     */
    public Map<Key, Boolean> reconcileAll() {
        Map<Key, Entry> snapshot;
        synchronized (this) {
            snapshot = new LinkedHashMap<>(entries);
        }
        Map<Key, Boolean> results = new LinkedHashMap<>();
        snapshot.forEach((key, entry) -> {
            try {
                results.put(key, entry.supervisor().reconcile());
            } catch (RuntimeException failure) {
                // Keep the group operation independent; the affected agent remains fail-closed.
                results.put(key, false);
            }
        });
        return Map.copyOf(results);
    }

    /** A group is ready only when it is non-empty and every registered agent is READY. */
    public boolean isReady() {
        List<Entry> snapshot;
        synchronized (this) {
            if (entries.isEmpty()) return false;
            snapshot = List.copyOf(entries.values());
        }
        return snapshot.stream().allMatch(entry -> entry.readiness().isReady()
                && entry.readiness().state() == AgentRuntimeRegistrationReadiness.State.READY);
    }

    public Map<Key, AgentRuntimeRegistrationReadiness.State> states() {
        Map<Key, Entry> snapshot;
        synchronized (this) {
            snapshot = new LinkedHashMap<>(entries);
        }
        Map<Key, AgentRuntimeRegistrationReadiness.State> result = new LinkedHashMap<>();
        snapshot.forEach((key, entry) -> result.put(key, entry.readiness().state()));
        return Map.copyOf(result);
    }

    public synchronized int size() {
        return entries.size();
    }

    /**
     * Stops accepting registrations and atomically detaches all current entries.
     * Each supervisor is closed outside the group monitor so slow persistence calls
     * cannot prevent unrelated membership queries or deadlock lifecycle callbacks.
     */
    @Override
    public void close() {
        List<Entry> snapshot;
        synchronized (this) {
            if (closed) return;
            closed = true;
            snapshot = List.copyOf(entries.values());
            entries.clear();
        }

        RuntimeException firstFailure = null;
        for (Entry entry : snapshot) {
            try {
                entry.supervisor().close();
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        if (firstFailure != null) throw firstFailure;
    }

    private synchronized Entry find(Key key) {
        return entries.get(key);
    }

    private static void closeAfterFailedRegistration(AgentRuntimeRegistrationSupervisor supervisor) {
        supervisor.close();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("registration group is closed");
    }

    public record Key(String tenantId, String agentId, String instanceId) {
        public Key {
            requireText(tenantId, "tenantId");
            requireText(agentId, "agentId");
            requireText(instanceId, "instanceId");
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }
    }

    private record Entry(
            AgentRuntimeRegistrationSupervisor supervisor,
            AgentRuntimeRegistrationReadiness readiness) { }
}
