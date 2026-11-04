package tech.kayys.andalus.execution.event.observability;

import tech.kayys.andalus.observability.ObservationEvent;

import java.time.Instant;
import java.util.Objects;

/** Metadata envelope for a terminally failed observation; payload remains the original event. */
public record ObservationDeadLetter(
        ObservationEvent event,
        int attempts,
        ObservationDeliveryFailure failureClass,
        String failureType,
        Instant failedAt) {
    public ObservationDeadLetter {
        Objects.requireNonNull(event, "event must not be null");
        if (attempts < 1) throw new IllegalArgumentException("attempts must be at least one");
        Objects.requireNonNull(failureClass, "failureClass must not be null");
        Objects.requireNonNull(failureType, "failureType must not be null");
        Objects.requireNonNull(failedAt, "failedAt must not be null");
    }
}
