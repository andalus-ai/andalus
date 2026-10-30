package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;
import tech.kayys.andalus.observability.ObservabilityPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BoundedAsyncObservabilityPublisherTest {

    @Test
    void dropOldestRetainsFreshestQueuedObservationWithoutBlockingProducer() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> delivered = new CopyOnWriteArrayList<>();
        ObservabilityPublisher delegate = event -> {
            if ("event-1".equals(event.id())) {
                firstStarted.countDown();
                await(releaseFirst);
            }
            delivered.add(event.id());
        };

        var publisher = new BoundedAsyncObservabilityPublisher(
                delegate, 1, BoundedAsyncObservabilityPublisher.OverflowPolicy.DROP_OLDEST,
                Duration.ofSeconds(2));
        try {
            publisher.publish(event("event-1"));
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            publisher.publish(event("event-2"));
            long before = System.nanoTime();
            publisher.publish(event("event-3"));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

            assertTrue(elapsedMillis < 100, "enqueue must not wait for a slow sink");
            assertEquals(1, publisher.queueSize());
            assertEquals(1, publisher.droppedCount());

            releaseFirst.countDown();
            publisher.close();

            assertEquals(List.of("event-1", "event-3"), delivered);
            assertEquals(2, publisher.deliveredCount());
            assertEquals(3, publisher.acceptedCount());
        } finally {
            releaseFirst.countDown();
            publisher.close();
        }
    }

    @Test
    void dropNewestPreservesAlreadyAcceptedBacklog() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> delivered = new CopyOnWriteArrayList<>();
        ObservabilityPublisher delegate = event -> {
            if ("event-1".equals(event.id())) {
                firstStarted.countDown();
                await(releaseFirst);
            }
            delivered.add(event.id());
        };

        var publisher = new BoundedAsyncObservabilityPublisher(
                delegate, 1, BoundedAsyncObservabilityPublisher.OverflowPolicy.DROP_NEWEST,
                Duration.ofSeconds(2));
        try {
            publisher.publish(event("event-1"));
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            publisher.publish(event("event-2"));
            publisher.publish(event("event-3"));

            assertEquals(1, publisher.droppedCount());
            releaseFirst.countDown();
            publisher.close();

            assertEquals(List.of("event-1", "event-2"), delivered);
        } finally {
            releaseFirst.countDown();
            publisher.close();
        }
    }

    @Test
    void closeDrainsAcceptedEventsWithinGracePeriod() {
        List<String> delivered = new CopyOnWriteArrayList<>();
        var publisher = new BoundedAsyncObservabilityPublisher(
                event -> delivered.add(event.id()), 8,
                BoundedAsyncObservabilityPublisher.OverflowPolicy.DROP_NEWEST,
                Duration.ofSeconds(2));

        publisher.publish(event("event-1"));
        publisher.publish(event("event-2"));
        publisher.close();

        assertEquals(List.of("event-1", "event-2"), delivered);
        assertEquals(0, publisher.droppedCount());
        assertFalse(publisher.isWorkerAlive());
    }

    @Test
    void countsDelegateFailuresAndContinuesProcessing() {
        List<String> attempted = new CopyOnWriteArrayList<>();
        var publisher = new BoundedAsyncObservabilityPublisher(event -> {
            attempted.add(event.id());
            if ("event-1".equals(event.id())) {
                throw new IllegalStateException("sink failure");
            }
        }, 8, BoundedAsyncObservabilityPublisher.OverflowPolicy.DROP_NEWEST,
                Duration.ofSeconds(2));

        publisher.publish(event("event-1"));
        publisher.publish(event("event-2"));
        publisher.close();

        assertEquals(List.of("event-1", "event-2"), attempted);
        assertEquals(1, publisher.failureCount());
        assertEquals(1, publisher.deliveredCount());
    }

    @Test
    void rejectsInvalidCapacityAndShutdownTimeout() {
        assertThrows(IllegalArgumentException.class, () ->
                new BoundedAsyncObservabilityPublisher(event -> { }, 0,
                        BoundedAsyncObservabilityPublisher.OverflowPolicy.DROP_OLDEST,
                        Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () ->
                new BoundedAsyncObservabilityPublisher(event -> { }, 1,
                        BoundedAsyncObservabilityPublisher.OverflowPolicy.DROP_OLDEST,
                        Duration.ofMillis(-1)));
    }

    private static ObservationEvent event(String id) {
        return new ObservationEvent(id, "execution-1", "trace-1", "TEST",
                Instant.parse("2026-10-30T00:00:00Z"), Map.of());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test interrupted", interrupted);
        }
    }
}
