package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;
import tech.kayys.andalus.observability.ObservabilityPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedAsyncObservabilityPublisherRetryTest {
    @Test
    void transientFailureRecoversWithinBoundedAttempts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(1);
        ObservabilityPublisher sink = event -> {
            if (calls.incrementAndGet() < 3) throw new IllegalStateException("temporary");
            delivered.countDown();
        };
        var publisher = new BoundedAsyncObservabilityPublisher(sink, 4,
                BoundedAsyncObservabilityPublisher.OverflowPolicy.DROP_NEWEST, Duration.ofSeconds(2),
                null, new ObservationDeliveryRetryPolicy(3, Duration.ofMillis(5), Duration.ofMillis(20)));
        try {
            publisher.publish(event());
            assertTrue(delivered.await(1, TimeUnit.SECONDS));
            assertEquals(3, publisher.deliveryAttemptCount());
            assertEquals(1, publisher.deliveredCount());
            assertEquals(0, publisher.failureCount());
        } finally { publisher.close(); }
    }

    @Test
    void exhaustedRetriesCountOneTerminalFailure() {
        AtomicInteger calls = new AtomicInteger();
        var publisher = new BoundedAsyncObservabilityPublisher(event -> {
            calls.incrementAndGet(); throw new IllegalStateException("sink down");
        }, 4, BoundedAsyncObservabilityPublisher.OverflowPolicy.DROP_NEWEST, Duration.ofSeconds(2),
                null, new ObservationDeliveryRetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(2)));
        publisher.publish(event());
        publisher.close();
        assertEquals(3, calls.get());
        assertEquals(3, publisher.deliveryAttemptCount());
        assertEquals(1, publisher.failureCount());
        assertEquals(0, publisher.deliveredCount());
    }

    @Test
    void shutdownInterruptsRetryBackoffAndIsBounded() throws Exception {
        CountDownLatch firstAttempt = new CountDownLatch(1);
        var publisher = new BoundedAsyncObservabilityPublisher(event -> {
            firstAttempt.countDown(); throw new IllegalStateException("retry");
        }, 4, BoundedAsyncObservabilityPublisher.OverflowPolicy.DROP_NEWEST, Duration.ofMillis(100),
                null, new ObservationDeliveryRetryPolicy(10, Duration.ofSeconds(5), Duration.ofSeconds(5)));
        publisher.publish(event());
        assertTrue(firstAttempt.await(1, TimeUnit.SECONDS));
        long start = System.nanoTime();
        publisher.close();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs < 500, "close should interrupt retry wait");
        assertFalse(publisher.isWorkerAlive());
        assertEquals(1, publisher.droppedCount());
    }

    private static ObservationEvent event() {
        return new ObservationEvent("retry-event", "execution-1", "trace-1", "TEST",
                Instant.now(), Map.of());
    }
}
