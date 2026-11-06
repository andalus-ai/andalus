package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ObservationDeadLetterParkingRecoveryLifecycleTest {
    @Test
    void reactivatesEntryAndRemovesItAfterQueueAccepts() {
        var store = new InMemoryObservationDeadLetterParkingStore(2);
        var parked = store.park(letter("recover"), "test").entry();
        var queue = new BoundedObservationDeadLetterQueue(2,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var recovery = new ObservationDeadLetterParkingRecovery(store, queue);

        var result = recovery.recover(parked.parkingId());

        assertEquals(ObservationDeadLetterParkingRecovery.Status.REACTIVATED, result.status());
        assertEquals(parked, result.entry());
        assertEquals(1, queue.size());
        assertTrue(store.find(parked.parkingId()).isEmpty());
        assertEquals(1, recovery.metrics().recoverySucceeded());
        assertEquals(1, store.metrics().removed());
    }

    @Test
    void retainsParkedEntryWhenActiveQueueIsFull() {
        var store = new InMemoryObservationDeadLetterParkingStore(2);
        var parked = store.park(letter("recover"), "test").entry();
        var queue = new BoundedObservationDeadLetterQueue(1,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        queue.offer(letter("already-active"));
        var recovery = new ObservationDeadLetterParkingRecovery(store, queue);

        var result = recovery.recover(parked.parkingId());

        assertEquals(ObservationDeadLetterParkingRecovery.Status.QUEUE_CAPACITY_REACHED, result.status());
        assertTrue(store.find(parked.parkingId()).isPresent());
        assertEquals(1, queue.size());
        assertEquals(1, recovery.metrics().recoveryQueueFull());
        assertEquals(0, store.metrics().removed());
    }

    @Test
    void reportsNotFoundWithoutChangingQueueOrStore() {
        var store = new InMemoryObservationDeadLetterParkingStore(2);
        var queue = new BoundedObservationDeadLetterQueue(1,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var recovery = new ObservationDeadLetterParkingRecovery(store, queue);

        var result = recovery.recover("missing");

        assertEquals(ObservationDeadLetterParkingRecovery.Status.NOT_FOUND, result.status());
        assertNull(result.entry());
        assertEquals(0, queue.size());
        assertEquals(1, recovery.metrics().recoveryNotFound());
    }

    @Test
    void rejectsBlankParkingIdBeforeRecordingRecovery() {
        var store = new InMemoryObservationDeadLetterParkingStore(1);
        var queue = new BoundedObservationDeadLetterQueue(1,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var recovery = new ObservationDeadLetterParkingRecovery(store, queue);

        assertThrows(IllegalArgumentException.class, () -> recovery.recover(" "));
        assertEquals(0, recovery.metrics().recoveryNotFound());
    }

    private static ObservationDeadLetter letter(String id) {
        var event = new ObservationEvent(id, "execution-1", "trace-1", "TEST",
                Instant.parse("2026-11-06T00:00:00Z"), Map.of());
        return new ObservationDeadLetter(event, 1, ObservationDeliveryFailure.UNKNOWN,
                IllegalStateException.class.getName(), Instant.parse("2026-11-06T00:00:01Z"));
    }
}
