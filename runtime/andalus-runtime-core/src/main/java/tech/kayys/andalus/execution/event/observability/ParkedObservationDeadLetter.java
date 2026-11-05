package tech.kayys.andalus.execution.event.observability;

import java.time.Instant;
import java.util.Objects;

/** A dead-letter entry retained for manual inspection or later recovery. */
public record ParkedObservationDeadLetter(
        String parkingId,
        ObservationDeadLetter deadLetter,
        String reason,
        Instant parkedAt) {
    public ParkedObservationDeadLetter {
        if (parkingId == null || parkingId.isBlank()) {
            throw new IllegalArgumentException("parkingId must not be blank");
        }
        Objects.requireNonNull(deadLetter, "deadLetter must not be null");
        reason = reason == null ? "" : reason;
        Objects.requireNonNull(parkedAt, "parkedAt must not be null");
    }
}
