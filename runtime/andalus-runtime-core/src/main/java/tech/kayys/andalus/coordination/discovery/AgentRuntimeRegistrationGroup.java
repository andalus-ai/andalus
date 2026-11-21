package tech.kayys.andalus.coordination.discovery;

import java.util.LinkedHashMap;
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
     */
    public synchronized Key register(AgentRuntimeRegistrationHost.Registration registration) {
        ensureOpen();
        Objects.requireNonNull(registration, "registration must not be null");
        Key key = new Key(registration.tenantId(), registration.agentId(), registration.instanceId());
        if (entries.containsKey(key)) {
            throw new IllegalStateException("Agent registration already exists: " + key);
        }

        AgentRuntimeRegistrationReadiness readiness = new AgentRuntimeRegistrationReadiness();
        AgentRuntimeRegistrationSupervisor supervisor = new AgentRuntimeRegistrationSupervisor(
                lifecycle, scheduler, readiness,
                registration.tenantId(), registration.agentId(), registration.instanceId(),
                registration.ownerId(), registration.heartbeatInterval(),
                registration.initialRetryDelay(), registration.maxRetryDelay(), observer);
        Entry entry = new Entry(supervisor, readiness);
        entries.put(key, entry);
        try {
            supervisor.start();
        } catch (RuntimeException failure) {
            entries.remove(key);
            supervisor.close();
            throw failure;
        }
        return key;
    }

    /** Publishes AVAILABLE only for the selected logical agent. */
    public synchronized boolean markAvailable(Key key) {
        Entry entry = entries.get(Objects.requireNonNull(key, "key must not be null"));
        return entry != null && entry.supervisor().markAvailable();
    }

    /** Withdraws only the selected logical agent while retaining its lease for recovery. */
    public synchronized boolean markUnavailable(Key key) {
        Entry entry = entries.get(Objects.requireNonNull(key, "key must not be null"));
        return entry != null && entry.supervisor().markUnavailable();
    }

    /** Removes one registration and performs fenced shutdown. */
    public synchronized boolean unregister(Key key) {
        Entry entry = entries.remove(Objects.requireNonNull(key, "key must not be null"));
        if (entry == null) return false;
        entry.supervisor().close();
        return true;
    }

    /** A group is ready only when it is non-empty and every registered agent is READY. */
    public synchronized boolean isReady() {
        return !entries.isEmpty()
                && entries.values().stream().allMatch(entry -> entry.readiness().isReady()
                        && entry.readiness().state() == AgentRuntimeRegistrationReadiness.State.READY);
    }

    public synchronized Map<Key, AgentRuntimeRegistrationReadiness.State> states() {
        Map<Key, AgentRuntimeRegistrationReadiness.State> snapshot = new LinkedHashMap<>();
        entries.forEach((key, entry) -> snapshot.put(key, entry.readiness().state()));
        return Map.copyOf(snapshot);
    }

    public synchronized int size() {
        return entries.size();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException firstFailure = null;
        for (Entry entry : entries.values()) {
            try {
                entry.supervisor().close();
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        entries.clear();
        if (firstFailure != null) throw firstFailure;
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
