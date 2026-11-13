package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Service-level concurrency tests for recovery ownership and queue handoff. */
class ObservationDeadLetterParkingRecoveryHandoffConcurrencyTest {

    @Test
    void competingRecoveryInstancesDoNotBothEnqueueTheSameParkedEntry() throws Exception {
        var delegate = new InMemoryObservationDeadLetterParkingStore(4);
        var parked = delegate.park(letter("competing-recovery"), "test").entry();
        var firstClaimReturned = new CountDownLatch(1);
        var secondClaimObserved = new CountDownLatch(1);
        var claimCalls = new AtomicInteger();
        LeasedObservationDeadLetterParkingStore gatedStore =
                (LeasedObservationDeadLetterParkingStore) Proxy.newProxyInstance(
                        LeasedObservationDeadLetterParkingStore.class.getClassLoader(),
                        new Class<?>[]{LeasedObservationDeadLetterParkingStore.class},
                        (proxy, method, args) -> {
                            try {
                                Object result = method.invoke(delegate, args);
                                if (method.getName().equals("claim")) {
                                    int call = claimCalls.incrementAndGet();
                                    if (call == 1) {
                                        firstClaimReturned.countDown();
                                        if (!secondClaimObserved.await(5, TimeUnit.SECONDS)) {
                                            throw new AssertionError("competing recovery did not attempt claim");
                                        }
                                    } else if (call == 2) {
                                        secondClaimObserved.countDown();
                                    }
                                }
                                return result;
                            } catch (InvocationTargetException exception) {
                                throw exception.getCause();
                            }
                        });

        var queue = new BoundedObservationDeadLetterQueue(4,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var metrics = new ObservationDeadLetterParkingMetrics();
        var first = new ObservationDeadLetterParkingRecovery(
                gatedStore, queue, metrics, "worker-a", Duration.ofSeconds(30));
        var second = new ObservationDeadLetterParkingRecovery(
                gatedStore, queue, metrics, "worker-b", Duration.ofSeconds(30));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var firstResult = pool.submit(() -> first.recover(parked.parkingId()));
            assertTrue(firstClaimReturned.await(5, TimeUnit.SECONDS));
            var secondResult = pool.submit(() -> second.recover(parked.parkingId()));

            var competing = secondResult.get(5, TimeUnit.SECONDS);
            var recovered = firstResult.get(5, TimeUnit.SECONDS);

            assertEquals(ObservationDeadLetterParkingRecovery.Status.RECOVERY_IN_PROGRESS,
                    competing.status());
            assertEquals(ObservationDeadLetterParkingRecovery.Status.REACTIVATED, recovered.status());
            assertEquals(1, queue.size(), "only the lease owner may hand off the entry");
            assertTrue(delegate.find(parked.parkingId()).isEmpty());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void leaseExpiryAfterRenewalIsReportedAsDuplicatePossibleWithoutLosingParkedCopy() {
        var clock = new MutableClock(Instant.parse("2026-11-13T10:00:00Z"));
        var delegate = new InMemoryObservationDeadLetterParkingStore(4, clock);
        var parked = delegate.park(letter("handoff-expiry"), "test").entry();
        LeasedObservationDeadLetterParkingStore expiringAfterRenewal =
                (LeasedObservationDeadLetterParkingStore) Proxy.newProxyInstance(
                        LeasedObservationDeadLetterParkingStore.class.getClassLoader(),
                        new Class<?>[]{LeasedObservationDeadLetterParkingStore.class},
                        (proxy, method, args) -> {
                            try {
                                Object result = method.invoke(delegate, args);
                                if (method.getName().equals("renew")) {
                                    clock.advance(Duration.ofSeconds(31));
                                }
                                return result;
                            } catch (InvocationTargetException exception) {
                                throw exception.getCause();
                            }
                        });
        var queue = new BoundedObservationDeadLetterQueue(2,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var recovery = new ObservationDeadLetterParkingRecovery(
                expiringAfterRenewal, queue, new ObservationDeadLetterParkingMetrics(),
                "worker-a", Duration.ofSeconds(30));

        var result = recovery.recover(parked.parkingId());

        assertEquals(ObservationDeadLetterParkingRecovery.Status.DUPLICATE_POSSIBLE, result.status());
        assertEquals(1, queue.size(), "queue handoff happened before lease completion was rejected");
        assertEquals(parked, delegate.find(parked.parkingId()).orElseThrow(),
                "failed fenced completion must preserve the parked copy");
    }

    private static ObservationDeadLetter letter(String id) {
        var event = new ObservationEvent(id, "execution-handoff", "trace-handoff", "HANDOFF_TEST",
                Instant.parse("2026-11-13T09:59:00Z"), Map.of());
        return new ObservationDeadLetter(event, 1, ObservationDeliveryFailure.UNKNOWN,
                IllegalStateException.class.getName(), Instant.parse("2026-11-13T09:59:01Z"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
