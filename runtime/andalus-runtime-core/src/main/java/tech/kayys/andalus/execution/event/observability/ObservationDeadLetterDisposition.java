package tech.kayys.andalus.execution.event.observability;

import java.util.Objects;

/**
 * Explicit consumer disposition for one observation dead-letter entry.
 *
 * <p>This is a contract value, not an acknowledgment implementation. The
 * component that owns the queue or durable store must apply the disposition
 * atomically with its own state transition where possible. RETRY and PARK
 * must not be interpreted as successful delivery.</p>
 */
public record ObservationDeadLetterDisposition(Action action, String reason) {
    public ObservationDeadLetterDisposition {
        Objects.requireNonNull(action, "action must not be null");
        reason = reason == null ? "" : reason;
    }

    /** Owner may acknowledge/remove after the handler's operation succeeds. */
    public static ObservationDeadLetterDisposition acknowledge() {
        return new ObservationDeadLetterDisposition(Action.ACKNOWLEDGE, "");
    }

    /** Owner retains or re-enqueues the entry for retry. */
    public static ObservationDeadLetterDisposition retry(String reason) {
        return new ObservationDeadLetterDisposition(Action.RETRY, reason);
    }

    /** Owner parks the entry for inspection; this is not acknowledgment. */
    public static ObservationDeadLetterDisposition park(String reason) {
        return new ObservationDeadLetterDisposition(Action.PARK, reason);
    }

    public enum Action {
        ACKNOWLEDGE,
        RETRY,
        PARK
    }
}
