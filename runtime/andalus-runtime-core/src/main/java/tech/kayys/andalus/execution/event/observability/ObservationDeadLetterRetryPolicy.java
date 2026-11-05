package tech.kayys.andalus.execution.event.observability;

import java.time.Duration;
import java.util.Objects;

/**
 * Deterministic bounded exponential-backoff policy for consumer-side DLQ retries.
 *
 * <p>{@code retryNumber} is the one-based consumer retry number, independent of
 * the original publisher delivery-attempt count in {@link ObservationDeadLetter}.
 * Only transient failures are retried by default; UNKNOWN can be enabled
 * explicitly. Permanent failures are never retried.</p>
 */
public final class ObservationDeadLetterRetryPolicy {
    private final int maxRetries;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double multiplier;
    private final boolean retryUnknown;

    public ObservationDeadLetterRetryPolicy(
            int maxRetries,
            Duration initialDelay,
            Duration maxDelay,
            double multiplier,
            boolean retryUnknown) {
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must not be negative");
        this.initialDelay = requirePositive(initialDelay, "initialDelay");
        this.maxDelay = requirePositive(maxDelay, "maxDelay");
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must be greater than or equal to initialDelay");
        }
        if (!Double.isFinite(multiplier) || multiplier < 1.0d) {
            throw new IllegalArgumentException("multiplier must be finite and at least 1.0");
        }
        this.maxRetries = maxRetries;
        this.multiplier = multiplier;
        this.retryUnknown = retryUnknown;
    }

    public Decision decide(ObservationDeadLetter deadLetter, int retryNumber) {
        Objects.requireNonNull(deadLetter, "deadLetter must not be null");
        if (retryNumber < 1) throw new IllegalArgumentException("retryNumber must be at least one");
        if (retryNumber > maxRetries || !isRetryable(deadLetter.failureClass())) {
            return new Decision(false, retryNumber, Duration.ZERO);
        }
        return new Decision(true, retryNumber, delayFor(retryNumber));
    }

    private boolean isRetryable(ObservationDeliveryFailure failureClass) {
        return failureClass == ObservationDeliveryFailure.TRANSIENT
                || (retryUnknown && failureClass == ObservationDeliveryFailure.UNKNOWN);
    }

    private Duration delayFor(int retryNumber) {
        double delayNanos = initialDelay.toNanos();
        double capNanos = maxDelay.toNanos();
        for (int i = 1; i < retryNumber && delayNanos < capNanos; i++) {
            delayNanos = Math.min(capNanos, delayNanos * multiplier);
        }
        if (!Double.isFinite(delayNanos) || delayNanos >= capNanos) return maxDelay;
        return Duration.ofNanos(Math.max(1L, (long) delayNanos));
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        try {
            value.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(name + " is too large", overflow);
        }
        return value;
    }

    public record Decision(boolean retry, int retryNumber, Duration delay) {
        public Decision {
            if (retryNumber < 1) throw new IllegalArgumentException("retryNumber must be at least one");
            Objects.requireNonNull(delay, "delay must not be null");
            if (delay.isNegative()) throw new IllegalArgumentException("delay must not be negative");
            if (!retry && !delay.isZero()) {
                throw new IllegalArgumentException("non-retry decisions must have zero delay");
            }
        }
    }
}
