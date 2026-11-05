package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ObservationDeadLetterRetryPolicyTest {
    @Test
    void retriesTransientFailuresWithCappedExponentialBackoff() {
        var policy = new ObservationDeadLetterRetryPolicy(5,
                Duration.ofMillis(100), Duration.ofMillis(250), 2.0d, false);
        var letter = letter(ObservationDeliveryFailure.TRANSIENT);

        assertEquals(Duration.ofMillis(100), policy.decide(letter, 1).delay());
        assertEquals(Duration.ofMillis(200), policy.decide(letter, 2).delay());
        assertEquals(Duration.ofMillis(250), policy.decide(letter, 3).delay());
        assertEquals(Duration.ofMillis(250), policy.decide(letter, 5).delay());
    }

    @Test
    void permanentAndUnknownFailuresAreNotRetriedByDefault() {
        var policy = new ObservationDeadLetterRetryPolicy(3,
                Duration.ofSeconds(1), Duration.ofSeconds(8), 2.0d, false);
        assertFalse(policy.decide(letter(ObservationDeliveryFailure.PERMANENT), 1).retry());
        assertFalse(policy.decide(letter(ObservationDeliveryFailure.UNKNOWN), 1).retry());
    }

    @Test
    void unknownFailuresCanBeExplicitlyEnabledAndRetryLimitIsEnforced() {
        var policy = new ObservationDeadLetterRetryPolicy(1,
                Duration.ofMillis(10), Duration.ofSeconds(1), 2.0d, true);
        assertTrue(policy.decide(letter(ObservationDeliveryFailure.UNKNOWN), 1).retry());
        var exhausted = policy.decide(letter(ObservationDeliveryFailure.TRANSIENT), 2);
        assertFalse(exhausted.retry());
        assertEquals(Duration.ZERO, exhausted.delay());
    }

    @Test
    void rejectsInvalidConfigurationAndRetryNumber() {
        assertThrows(IllegalArgumentException.class, () -> new ObservationDeadLetterRetryPolicy(
                -1, Duration.ofSeconds(1), Duration.ofSeconds(2), 2.0d, false));
        assertThrows(IllegalArgumentException.class, () -> new ObservationDeadLetterRetryPolicy(
                1, Duration.ZERO, Duration.ofSeconds(2), 2.0d, false));
        assertThrows(IllegalArgumentException.class, () -> new ObservationDeadLetterRetryPolicy(
                1, Duration.ofSeconds(2), Duration.ofSeconds(1), 2.0d, false));
        assertThrows(IllegalArgumentException.class, () -> new ObservationDeadLetterRetryPolicy(
                1, Duration.ofSeconds(1), Duration.ofSeconds(2), 0.5d, false));
        var policy = new ObservationDeadLetterRetryPolicy(1,
                Duration.ofSeconds(1), Duration.ofSeconds(2), 2.0d, false);
        assertThrows(IllegalArgumentException.class, () -> policy.decide(
                letter(ObservationDeliveryFailure.TRANSIENT), 0));
    }

    private static ObservationDeadLetter letter(ObservationDeliveryFailure failure) {
        var event = new ObservationEvent("event-1", "execution-1", "trace-1", "TEST",
                Instant.now(), Map.of());
        return new ObservationDeadLetter(event, 1, failure,
                IllegalStateException.class.getName(), Instant.now());
    }
}
