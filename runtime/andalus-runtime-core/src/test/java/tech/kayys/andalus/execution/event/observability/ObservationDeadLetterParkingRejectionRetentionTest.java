package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ObservationDeadLetterParkingRejectionRetentionTest {
    @Test
    void parkingCapacityRejectionRequeuesWithoutEvictingExistingLetters() {
        var queue = new BoundedObservationDeadLetterQueue(2,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var rejectedPark = letter("rejected-park");
        var existing = letter("existing");
        queue.offer(rejectedPark);
        queue.offer(existing);

        var store = new InMemoryObservationDeadLetterParkingStore(1);
        store.park(letter("already-parked"), "manual");
        var consumer = ObservationDeadLetterConsumer.withDispositionHandler(queue,
                ignored -> { queue.offer(letter("arrived-during-handler")); return ObservationDeadLetterDisposition.park("triage"); });
        var parking = new ObservationDeadLetterParkingConsumer(consumer, store);

        var result = parking.consume(1);

        assertEquals(1, result.rejectedCount());
        assertEquals(1, result.requeuedCount());
        assertEquals(0, result.unretainedCount());
        assertSame(rejectedPark, result.requeued().get(0).deadLetter());
        assertEquals(2, queue.size());
        assertSame(existing, queue.poll());
        assertSame(rejectedPark, queue.poll());
    }

    @Test
    void reportsUnretainedWhenActiveQueueIsAlsoFull() {
        var queue = new BoundedObservationDeadLetterQueue(1,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var rejectedPark = letter("rejected-park");
        queue.offer(rejectedPark);

        var store = new InMemoryObservationDeadLetterParkingStore(1);
        store.park(letter("already-parked"), "manual");
        var consumer = ObservationDeadLetterConsumer.withDispositionHandler(queue,
                ignored -> { queue.offer(letter("arrived-during-handler")); return ObservationDeadLetterDisposition.park("triage"); });
        var parking = new ObservationDeadLetterParkingConsumer(consumer, store);

        var result = parking.consume(1);

        assertEquals(1, result.rejectedCount());
        assertEquals(0, result.requeuedCount());
        assertEquals(1, result.unretainedCount());
        assertFalse(result.rejected().get(0).requeued());
        assertEquals(1, queue.size());
        assertNotNull(queue.poll());
    }

    private static ObservationDeadLetter letter(String id) {
        var event = new ObservationEvent(id, "execution-1", "trace-1", "TEST",
                Instant.parse("2026-11-06T00:00:00Z"), Map.of());
        return new ObservationDeadLetter(event, 1, ObservationDeliveryFailure.UNKNOWN,
                IllegalStateException.class.getName(), Instant.parse("2026-11-06T00:00:01Z"));
    }
}
