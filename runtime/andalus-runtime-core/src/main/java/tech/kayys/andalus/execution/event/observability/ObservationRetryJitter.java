package tech.kayys.andalus.execution.event.observability;

import java.time.Duration;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Applies bounded equal-jitter to an already calculated retry delay.
 *
 * <p>Use this as an opt-in scheduling layer after deterministic exponential
 * backoff. The random source is injectable for repeatable tests. The returned
 * delay is always positive and no greater than the supplied cap.</p>
 */
public final class ObservationRetryJitter {
    private final double fraction;
    private final DoubleSupplier random;

    public ObservationRetryJitter(double fraction, DoubleSupplier random) {
        if (!Double.isFinite(fraction) || fraction < 0.0d || fraction > 1.0d) {
            throw new IllegalArgumentException("fraction must be between 0 and 1");
        }
        this.fraction = fraction;
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    /** Returns a delay in [base * (1 - fraction), base], clipped to cap. */
    public Duration apply(Duration base, Duration cap) {
        Objects.requireNonNull(base, "base must not be null");
        Objects.requireNonNull(cap, "cap must not be null");
        if (base.isNegative() || cap.isNegative()) {
            throw new IllegalArgumentException("delays must not be negative");
        }
        if (base.isZero() || cap.isZero() || fraction == 0.0d) {
            return base.compareTo(cap) > 0 ? cap : base;
        }
        double sample = random.getAsDouble();
        if (!Double.isFinite(sample) || sample < 0.0d || sample > 1.0d) {
            throw new IllegalStateException("random source must return a value between 0 and 1");
        }
        long baseNanos = base.toNanos();
        long capNanos = cap.toNanos();
        long lower = Math.max(1L, (long) (baseNanos * (1.0d - fraction)));
        long upper = Math.min(baseNanos, capNanos);
        if (lower > upper) lower = upper;
        long result = lower + (long) ((upper - lower) * sample);
        return Duration.ofNanos(Math.max(1L, Math.min(result, capNanos)));
    }
}
