package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ObservationDeliveryRetryPolicyTest {

    @Test
    void exponentialBackoffIsBoundedAndAttemptCountIncludesInitialTry() {
        var policy = new ObservationDeliveryRetryPolicy(
                4, Duration.ofMillis(100), Duration.ofMillis(250));

        assertEquals(Duration.ZERO, policy.backoffBeforeAttempt(1));
        assertEquals(Duration.ofMillis(100), policy.backoffBeforeAttempt(2));
        assertEquals(Duration.ofMillis(200), policy.backoffBeforeAttempt(3));
        assertEquals(Duration.ofMillis(250), policy.backoffBeforeAttempt(4));
        assertEquals(4, policy.maxAttempts());
    }

    @Test
    void noRetryPolicyHasNoDelay() {
        var policy = ObservationDeliveryRetryPolicy.noRetries();
        assertEquals(1, policy.maxAttempts());
        assertEquals(Duration.ZERO, policy.backoffBeforeAttempt(2));
    }

    @Test
    void rejectsInvalidBounds() {
        assertThrows(IllegalArgumentException.class, () ->
                new ObservationDeliveryRetryPolicy(0, Duration.ZERO, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                new ObservationDeliveryRetryPolicy(2, Duration.ofSeconds(2), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () ->
                new ObservationDeliveryRetryPolicy(2, Duration.ofMillis(-1), Duration.ZERO));
    }
}
