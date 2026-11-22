package tech.kayys.andalus.coordination.discovery;

/**
 * Runtime-owned, low-cardinality observation hook for registration sessions.
 * Implementations must be fast and must not perform blocking I/O.
 */
@FunctionalInterface
public interface AgentRuntimeRegistrationObserver {

    enum Event {
        ACQUIRED,
        AVAILABLE,
        UNAVAILABLE,
        RENEWED,
        RENEWAL_REJECTED,
        STATUS_REJECTED,
        RENEWAL_ERROR,
        REVOKED,
        REVOCATION_REJECTED,
        OPERATION_ERROR,
        SCHEDULING_ERROR,
        RECOVERY_SCHEDULING_REJECTED
    }

    AgentRuntimeRegistrationObserver NOOP = event -> { };

    void onEvent(Event event);

    /** Records elapsed renewal-store latency; implementations may ignore it. */
    default void onRenewalDuration(long elapsedNanos) { }
}
