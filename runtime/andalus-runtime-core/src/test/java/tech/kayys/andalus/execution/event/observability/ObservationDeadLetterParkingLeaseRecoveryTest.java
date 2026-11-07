package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ObservationDeadLetterParkingLeaseRecoveryTest {
    @Test
    void claimsThenCompletesParkedEntryAfterQueueAcceptsIt() {
        var store = new InMemoryObservationDeadLetterParkingStore(4);
        var parked = store.park(letter("recover-me"), "test").entry();
        var queue = new BoundedObservationDeadLetterQueue(2,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var metrics = new ObservationDeadLetterParkingMetrics();
        var recovery = new ObservationDeadLetterParkingRecovery(
                store, queue, metrics, "worker-a", Duration.ofSeconds(30));

        var result = recovery.recover(parked.parkingId());

        assertEquals(ObservationDeadLetterParkingRecovery.Status.REACTIVATED, result.status());
        assertEquals(parked, result.entry());
        assertEquals(1, queue.size());
        assertTrue(store.find(parked.parkingId()).isEmpty());
        assertEquals(1, recovery.metrics().recoverySucceeded());
    }

    @Test
    void reportsClaimContentionWithoutOfferingAnotherQueueCopy() {
        var store = new InMemoryObservationDeadLetterParkingStore(4);
        var parked = store.park(letter("contended"), "test").entry();
        assertTrue(store.claim(parked.parkingId(), "worker-a", Duration.ofSeconds(30)).claimed());
        var queue = new BoundedObservationDeadLetterQueue(2,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var recovery = new ObservationDeadLetterParkingRecovery(
                store, queue, new ObservationDeadLetterParkingMetrics(),
                "worker-b", Duration.ofSeconds(30));

        var result = recovery.recover(parked.parkingId());

        assertEquals(ObservationDeadLetterParkingRecovery.Status.RECOVERY_IN_PROGRESS, result.status());
        assertNull(result.entry());
        assertEquals(0, queue.size());
        assertEquals(1, recovery.metrics().recoveryInProgress());
        assertTrue(store.find(parked.parkingId()).isPresent());
    }

    @Test
    void releasesClaimWhenQueueIsFullSoAnotherAttemptCanOwnIt() {
        var store = new InMemoryObservationDeadLetterParkingStore(4);
        var parked = store.park(letter("queue-full"), "test").entry();
        var queue = new BoundedObservationDeadLetterQueue(1,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        assertTrue(queue.offerWithoutEviction(letter("already-queued")));
        var recovery = new ObservationDeadLetterParkingRecovery(
                store, queue, new ObservationDeadLetterParkingMetrics(),
                "worker-a", Duration.ofSeconds(30));

        var result = recovery.recover(parked.parkingId());

        assertEquals(ObservationDeadLetterParkingRecovery.Status.QUEUE_CAPACITY_REACHED, result.status());
        assertEquals(1, queue.size());
        assertTrue(store.claim(parked.parkingId(), "worker-b", Duration.ofSeconds(30)).claimed(),
                "queue-capacity failure must release the recovery lease");
        assertEquals(1, recovery.metrics().recoveryQueueFull());
    }

    @Test
    void validatesExplicitLeaseConfiguration() {
        var store = new InMemoryObservationDeadLetterParkingStore(1);
        var queue = new BoundedObservationDeadLetterQueue(1,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var metrics = new ObservationDeadLetterParkingMetrics();

        assertThrows(IllegalArgumentException.class,
                () -> new ObservationDeadLetterParkingRecovery(store, queue, metrics, " ", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ObservationDeadLetterParkingRecovery(store, queue, metrics, "worker", Duration.ZERO));
    }

    private static ObservationDeadLetter letter(String id) {
        var event = new ObservationEvent(id, "execution-1", "trace-1", "TEST",
                Instant.parse("2026-11-06T10:00:00Z"), Map.of());
        return new ObservationDeadLetter(event, 1, ObservationDeliveryFailure.UNKNOWN,
                IllegalStateException.class.getName(), Instant.parse("2026-11-06T10:00:01Z"));
    }
}
