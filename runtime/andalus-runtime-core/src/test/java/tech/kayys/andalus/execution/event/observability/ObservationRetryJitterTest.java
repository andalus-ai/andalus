package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ObservationRetryJitterTest {
    @Test
    void zeroSampleReturnsLowerBoundAndOneReturnsUpperBound() {
        var jitter = new ObservationRetryJitter(0.5d, () -> 0.0d);
        assertEquals(Duration.ofMillis(500), jitter.apply(Duration.ofSeconds(1), Duration.ofSeconds(2)));
        var upper = new ObservationRetryJitter(0.5d, () -> 1.0d);
        assertEquals(Duration.ofSeconds(1), upper.apply(Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    @Test
    void clipsToCapAndPreservesZeroFraction() {
        var jitter = new ObservationRetryJitter(0.8d, () -> 0.5d);
        assertEquals(Duration.ofMillis(400), jitter.apply(Duration.ofSeconds(1), Duration.ofMillis(400)));
        var none = new ObservationRetryJitter(0.0d, () -> 0.5d);
        assertEquals(Duration.ofSeconds(1), none.apply(Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    @Test
    void rejectsInvalidFractionAndRandomSample() {
        assertThrows(IllegalArgumentException.class, () -> new ObservationRetryJitter(1.1d, () -> 0.5d));
        var invalid = new ObservationRetryJitter(0.5d, () -> Double.NaN);
        assertThrows(IllegalStateException.class,
                () -> invalid.apply(Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }
}
