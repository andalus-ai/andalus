package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ObservationDeadLetterRetrySchedulerTest {

    @Test
    void schedulesRetryAsynchronouslyAndReleasesCapacity() throws Exception {
        var invoked = new CountDownLatch(1);
        var scheduler = new ObservationDeadLetterRetryScheduler(
                policy(Duration.ofMillis(1)), 1, "dlq-retry-test", failure -> fail(failure.cause()));

        try {
            var result = scheduler.schedule(letter(ObservationDeliveryFailure.TRANSIENT), 1,
                    ignored -> invoked.countDown());

            assertEquals(ObservationDeadLetterRetryScheduler.ScheduleStatus.SCHEDULED, result.status());
            assertTrue(invoked.await(2, TimeUnit.SECONDS));
            assertEquals(1, scheduler.availableCapacity());
        } finally {
            scheduler.close();
        }
    }

    @Test
    void rejectsWorkWhenBoundedCapacityIsFullAndLeavesOwnershipWithCaller() {
        var scheduler = new ObservationDeadLetterRetryScheduler(
                policy(Duration.ofSeconds(30)), 1, "dlq-retry-capacity-test", ignored -> { });

        try {
            var first = scheduler.schedule(letter(ObservationDeliveryFailure.TRANSIENT), 1, ignored -> { });
            var second = scheduler.schedule(letter(ObservationDeliveryFailure.TRANSIENT), 1, ignored -> { });

            assertEquals(ObservationDeadLetterRetryScheduler.ScheduleStatus.SCHEDULED, first.status());
            assertEquals(ObservationDeadLetterRetryScheduler.ScheduleStatus.CAPACITY_REJECTED, second.status());
            assertEquals(0, scheduler.availableCapacity());
        } finally {
            scheduler.close();
        }
    }

    @Test
    void reportsHandlerFailureAndReleasesCapacity() throws Exception {
        var reported = new CountDownLatch(1);
        var failureRef = new AtomicReference<ObservationDeadLetterRetryScheduler.RetryFailure>();
        var scheduler = new ObservationDeadLetterRetryScheduler(
                policy(Duration.ofMillis(1)), 1, "dlq-retry-failure-test", failure -> {
                    failureRef.set(failure);
                    reported.countDown();
                });

        try {
            var deadLetter = letter(ObservationDeliveryFailure.TRANSIENT);
            assertEquals(ObservationDeadLetterRetryScheduler.ScheduleStatus.SCHEDULED,
                    scheduler.schedule(deadLetter, 1, ignored -> {
                        throw new IllegalStateException("replay failed");
                    }).status());

            assertTrue(reported.await(2, TimeUnit.SECONDS));
            assertSame(deadLetter, failureRef.get().deadLetter());
            assertEquals(1, failureRef.get().retryNumber());
            assertInstanceOf(IllegalStateException.class, failureRef.get().cause());
            assertEquals(1, scheduler.availableCapacity());
        } finally {
            scheduler.close();
        }
    }

    @Test
    void refusesSchedulingAfterCloseAndDoesNotRetryPermanentFailures() {
        var scheduler = new ObservationDeadLetterRetryScheduler(
                policy(Duration.ofSeconds(1)), 1, "dlq-retry-close-test", ignored -> { });

        assertEquals(ObservationDeadLetterRetryScheduler.ScheduleStatus.NOT_RETRYABLE,
                scheduler.schedule(letter(ObservationDeliveryFailure.PERMANENT), 1, ignored -> { }).status());
        scheduler.close();
        assertTrue(scheduler.isClosed());
        assertEquals(ObservationDeadLetterRetryScheduler.ScheduleStatus.CLOSED,
                scheduler.schedule(letter(ObservationDeliveryFailure.TRANSIENT), 1, ignored -> { }).status());
    }

    private static ObservationDeadLetterRetryPolicy policy(Duration delay) {
        return new ObservationDeadLetterRetryPolicy(3, delay, delay, 1.0d, false);
    }

    private static ObservationDeadLetter letter(ObservationDeliveryFailure failure) {
        var event = new ObservationEvent("event-1", "execution-1", "trace-1", "TEST",
                Instant.now(), Map.of());
        return new ObservationDeadLetter(event, 1, failure,
                IllegalStateException.class.getName(), Instant.now());
    }
}
