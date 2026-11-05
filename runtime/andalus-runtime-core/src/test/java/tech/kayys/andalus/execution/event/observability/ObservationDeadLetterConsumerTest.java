package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ObservationDeadLetterConsumerTest {
    @Test
    void consumesBoundedBatchAndReportsSuccessfulHandling() {
        var queue = queueWith("one", "two", "three");
        var calls = new AtomicInteger();
        var consumer = new ObservationDeadLetterConsumer(queue, ignored -> calls.incrementAndGet());

        var result = consumer.consume(2);

        assertEquals(2, result.polled());
        assertEquals(2, result.handled());
        assertEquals(0, result.failed());
        assertTrue(result.successful());
        assertEquals(2, calls.get());
        assertEquals(1, queue.size());
    }

    @Test
    void returnsFailedEntriesInsteadOfSilentlyDiscardingHandlerFailures() {
        var first = letter("first");
        var second = letter("second");
        var queue = new BoundedObservationDeadLetterQueue(4,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        queue.offer(first);
        queue.offer(second);
        var consumer = new ObservationDeadLetterConsumer(queue, deadLetter -> {
            if (deadLetter == first) throw new IllegalStateException("temporary failure");
        });

        var result = consumer.consume(2);

        assertEquals(2, result.polled());
        assertEquals(1, result.handled());
        assertEquals(1, result.failed());
        assertSame(first, result.failures().get(0).deadLetter());
        assertInstanceOf(IllegalStateException.class, result.failures().get(0).cause());
        assertThrows(UnsupportedOperationException.class,
                () -> result.failures().clear());
        assertEquals(0, queue.size());
    }

    @Test
    void emptyQueueReturnsSuccessfulEmptyResult() {
        var queue = new BoundedObservationDeadLetterQueue(2,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var consumer = new ObservationDeadLetterConsumer(queue, ignored -> fail("no entry expected"));

        var result = consumer.consume(1);

        assertEquals(0, result.polled());
        assertEquals(0, result.handled());
        assertTrue(result.successful());
    }

    @Test
    void invalidBatchSizeIsRejected() {
        var queue = new BoundedObservationDeadLetterQueue(2,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var consumer = new ObservationDeadLetterConsumer(queue, ignored -> { });
        assertThrows(IllegalArgumentException.class, () -> consumer.consume(0));
    }

    private static BoundedObservationDeadLetterQueue queueWith(String... ids) {
        var queue = new BoundedObservationDeadLetterQueue(8,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        for (String id : ids) queue.offer(letter(id));
        return queue;
    }

    private static ObservationDeadLetter letter(String id) {
        var event = new ObservationEvent(id, "execution-1", "trace-1", "TEST",
                Instant.now(), Map.of());
        return new ObservationDeadLetter(event, 1, ObservationDeliveryFailure.UNKNOWN,
                IllegalStateException.class.getName(), Instant.now());
    }
}
