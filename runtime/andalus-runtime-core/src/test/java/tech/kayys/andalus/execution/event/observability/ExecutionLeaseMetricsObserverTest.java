package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.harness.fabric.ExecutionLeaseObservation;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionLeaseMetricsObserverTest {
    @Test
    void tracksRenewalOutcomesAndActiveLeaseCount() {
        var observer = new ExecutionLeaseMetricsObserver(null, java.time.Clock.systemUTC());
        observer.onObservation(observation(ExecutionLeaseObservation.Type.ACQUIRED, null));
        observer.onObservation(observation(ExecutionLeaseObservation.Type.RENEWAL_SUCCEEDED, Duration.ofMillis(12)));
        observer.onObservation(observation(ExecutionLeaseObservation.Type.RENEWAL_REJECTED, Duration.ofMillis(4)));
        observer.onObservation(observation(ExecutionLeaseObservation.Type.RENEWAL_FAILED, Duration.ofMillis(8)));
        observer.onObservation(observation(ExecutionLeaseObservation.Type.OWNERSHIP_LOST, null));

        assertEquals(1, observer.activeLeaseCount());
        assertEquals(1, observer.renewalSuccessCount());
        assertEquals(1, observer.renewalRejectionCount());
        assertEquals(1, observer.renewalFailureCount());
        assertEquals(1, observer.ownershipLossCount());

        observer.onObservation(observation(ExecutionLeaseObservation.Type.RELEASED, null));
        assertEquals(0, observer.activeLeaseCount());
        assertTrue(observer.heartbeatFresh(Duration.ofSeconds(1)));
    }

    @Test
    void heartbeatHealthOnlyAppliesAfterAHeartbeatHasBeenObserved() {
        var observer = new ExecutionLeaseMetricsObserver(null, java.time.Clock.systemUTC());
        observer.onObservation(observation(ExecutionLeaseObservation.Type.ACQUIRED, null));
        assertTrue(observer.heartbeatFresh(Duration.ZERO),
                "bounded work without heartbeats is not considered unhealthy");

        observer.onObservation(observation(ExecutionLeaseObservation.Type.RENEWAL_SUCCEEDED, Duration.ofMillis(1)));
        assertTrue(observer.heartbeatFresh(Duration.ofSeconds(1)));
        assertTrue(observer.millisSinceLastHeartbeat() >= 0);
        assertFalse(observer.heartbeatFresh(Duration.ZERO),
                "a heartbeat becomes stale after its freshness window");
    }

    private static ExecutionLeaseObservation observation(
            ExecutionLeaseObservation.Type type, Duration duration) {
        return new ExecutionLeaseObservation(type, "exec-test", 7, Instant.now(), duration, null);
    }
}
