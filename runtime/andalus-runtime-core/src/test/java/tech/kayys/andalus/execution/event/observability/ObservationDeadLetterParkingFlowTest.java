package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ObservationDeadLetterParkingFlowTest {
    @Test
    void parkDispositionIsStoredAndCapacityFailureIsExplicit() {
        var letter = letter("park");
        var queue = new BoundedObservationDeadLetterQueue(2,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        queue.offer(letter);
        var consumer = ObservationDeadLetterConsumer.withDispositionHandler(queue,
                ignored -> ObservationDeadLetterDisposition.park("review"));
        var store = new InMemoryObservationDeadLetterParkingStore(1);
        store.park(letter("already-parked"), "existing");
        var adapter = new ObservationDeadLetterParkingConsumer(consumer, store);

        var result = adapter.consume(1);

        assertEquals(0, result.storedCount());
        assertEquals(1, result.rejectedCount());
        assertEquals(ObservationDeadLetterParkingStore.ParkStatus.CAPACITY_REACHED,
                result.rejected().get(0).status());
        assertEquals(1, store.size());
    }

    @Test
    void recoveryKeepsParkedEntryWhenActiveQueueIsFull() {
        var store = new InMemoryObservationDeadLetterParkingStore(2);
        var parked = store.park(letter("recover"), "manual").entry();
        var queue = new BoundedObservationDeadLetterQueue(1,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        queue.offer(letter("occupy"));
        var recovery = new ObservationDeadLetterParkingRecovery(store, queue);

        var result = recovery.recover(parked.parkingId());

        assertEquals(ObservationDeadLetterParkingRecovery.Status.QUEUE_CAPACITY_REACHED,
                result.status());
        assertEquals(1, store.size());
        assertEquals("occupy", queue.poll().event().eventId());
    }

    @Test
    void recoveryReactivatesAndRemovesParkingRecord() {
        var store = new InMemoryObservationDeadLetterParkingStore(2);
        var parked = store.park(letter("recover"), "manual").entry();
        var queue = new BoundedObservationDeadLetterQueue(1,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var recovery = new ObservationDeadLetterParkingRecovery(store, queue);

        var result = recovery.recover(parked.parkingId());

        assertTrue(result.reactivated());
        assertEquals(0, store.size());
        assertEquals("recover", queue.poll().event().eventId());
    }

    private static ObservationDeadLetter letter(String id) {
        var event = new ObservationEvent(id, "execution-1", "trace-1", "TEST",
                Instant.parse("2026-11-05T00:00:00Z"), Map.of());
        return new ObservationDeadLetter(event, 1, ObservationDeliveryFailure.UNKNOWN,
                IllegalStateException.class.getName(), Instant.parse("2026-11-05T00:00:01Z"));
    }
}
