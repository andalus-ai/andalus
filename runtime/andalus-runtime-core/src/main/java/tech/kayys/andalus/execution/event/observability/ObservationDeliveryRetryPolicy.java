package tech.kayys.andalus.execution.event.observability;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounded retry policy for transient observation sink failures.
 * maxAttempts includes the initial delivery attempt (1 means no retry).
 */
public record ObservationDeliveryRetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {

    public ObservationDeliveryRetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least one");
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff must not be null");
        Objects.requireNonNull(maxBackoff, "maxBackoff must not be null");
        if (initialBackoff.isNegative() || maxBackoff.isNegative()) {
            throw new IllegalArgumentException("backoff durations must not be negative");
        }
        if (initialBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException("initialBackoff must not exceed maxBackoff");
        }
    }

    public static ObservationDeliveryRetryPolicy noRetries() {
        return new ObservationDeliveryRetryPolicy(1, Duration.ZERO, Duration.ZERO);
    }

    public Duration backoffBeforeAttempt(int attemptNumber) {
        if (attemptNumber < 2 || maxAttempts == 1 || initialBackoff.isZero()) {
            return Duration.ZERO;
        }
        long delay = initialBackoff.toMillis();
        long cap = maxBackoff.toMillis();
        for (int attempt = 2; attempt < attemptNumber && delay < cap; attempt++) {
            delay = delay > cap / 2 ? cap : Math.min(cap, delay * 2);
        }
        return Duration.ofMillis(Math.min(delay, cap));
    }
}
