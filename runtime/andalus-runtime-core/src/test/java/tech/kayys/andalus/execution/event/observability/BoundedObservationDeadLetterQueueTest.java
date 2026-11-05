package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BoundedObservationDeadLetterQueueTest {
    @Test
    void dropNewestPreservesExistingEntriesAndReportsCapacityMetrics() {
        var queue = new BoundedObservationDeadLetterQueue(2,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var first = letter("first");
        var second = letter("second");

        assertTrue(queue.offer(first));
        assertTrue(queue.offer(second));
        assertFalse(queue.offer(letter("third")));

        assertEquals(2, queue.size());
        assertEquals(2, queue.capacity());
        assertEquals(0, queue.remainingCapacity());
        assertEquals(1.0, queue.utilization());
        assertEquals(2, queue.acceptedCount());
        assertEquals(1, queue.droppedCount());
        assertSame(first, queue.poll());
        assertSame(second, queue.poll());
        assertEquals(0.0, queue.utilization());
    }

    @Test
    void dropOldestRetainsNewestAndCountsEvictionAsAcceptedAndDropped() {
        var queue = new BoundedObservationDeadLetterQueue(2,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_OLDEST);
        var first = letter("first");
        var second = letter("second");
        var third = letter("third");

        assertTrue(queue.offer(first));
        assertTrue(queue.offer(second));
        assertTrue(queue.offer(third));

        assertEquals(2, queue.size());
        assertEquals(2, queue.capacity());
        assertEquals(0, queue.remainingCapacity());
        assertEquals(1.0, queue.utilization());
        assertEquals(3, queue.acceptedCount());
        assertEquals(1, queue.droppedCount());
        assertSame(second, queue.poll());
        assertSame(third, queue.poll());
    }

    @Test
    void rejectsInvalidCapacityAndNullLetter() {
        assertThrows(IllegalArgumentException.class, () ->
                new BoundedObservationDeadLetterQueue(0,
                        BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST));
        var queue = new BoundedObservationDeadLetterQueue(1,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        assertThrows(NullPointerException.class, () -> queue.offer(null));
    }

    private static ObservationDeadLetter letter(String id) {
        var event = new ObservationEvent(id, "execution-1", "trace-1", "TEST",
                Instant.now(), Map.of());
        return new ObservationDeadLetter(event, 1, ObservationDeliveryFailure.UNKNOWN,
                IllegalStateException.class.getName(), Instant.now());
    }
}
