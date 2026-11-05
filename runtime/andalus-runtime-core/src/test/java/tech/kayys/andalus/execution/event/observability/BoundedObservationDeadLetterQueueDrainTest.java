package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BoundedObservationDeadLetterQueueDrainTest {
    @Test
    void drainRemovesAtMostRequestedCountInFifoOrder() {
        var queue = new BoundedObservationDeadLetterQueue(4,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var first = letter("first");
        var second = letter("second");
        var third = letter("third");
        queue.offer(first);
        queue.offer(second);
        queue.offer(third);

        var batch = queue.drain(2);

        assertEquals(2, batch.size());
        assertSame(first, batch.get(0));
        assertSame(second, batch.get(1));
        assertThrows(UnsupportedOperationException.class, () -> batch.add(letter("fourth")));
        assertEquals(1, queue.size());
        assertSame(third, queue.poll());
    }

    @Test
    void drainEmptyQueueReturnsEmptyListAndRejectsInvalidBatchSize() {
        var queue = new BoundedObservationDeadLetterQueue(2,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        assertTrue(queue.drain(1).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> queue.drain(0));
        assertThrows(IllegalArgumentException.class, () -> queue.drain(-1));
    }

    private static ObservationDeadLetter letter(String id) {
        var event = new ObservationEvent(id, "execution-1", "trace-1", "TEST",
                Instant.now(), Map.of());
        return new ObservationDeadLetter(event, 1, ObservationDeliveryFailure.UNKNOWN,
                IllegalStateException.class.getName(), Instant.now());
    }
}
