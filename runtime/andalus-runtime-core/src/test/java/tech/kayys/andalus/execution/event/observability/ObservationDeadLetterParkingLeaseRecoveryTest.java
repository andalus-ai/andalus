package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class ObservationDeadLetterParkingLeaseRecoveryTest {
    @Test
    void claimsThenCompletesParkedEntryAfterQueueAcceptsIt() {
        var store = new InMemoryObservationDeadLetterParkingStore(4);
        var parked = store.park(letter("recover-me"), "test").entry();
        var queue = new BoundedObservationDeadLetterQueue(2,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var metrics = new ObservationDeadLetterParkingMetrics();
        var recovery = new ObservationDeadLetterParkingRecovery(
                store, queue, metrics, "worker-a", Duration.ofSeconds(30));

        var result = recovery.recover(parked.parkingId());

        assertEquals(ObservationDeadLetterParkingRecovery.Status.REACTIVATED, result.status());
        assertEquals(parked, result.entry());
        assertEquals(1, queue.size());
        assertTrue(store.find(parked.parkingId()).isEmpty());
        assertEquals(1, recovery.metrics().recoverySucceeded());
    }

    @Test
    void reportsClaimContentionWithoutOfferingAnotherQueueCopy() {
        var store = new InMemoryObservationDeadLetterParkingStore(4);
        var parked = store.park(letter("contended"), "test").entry();
        assertTrue(store.claim(parked.parkingId(), "worker-a", Duration.ofSeconds(30)).claimed());
        var queue = new BoundedObservationDeadLetterQueue(2,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var recovery = new ObservationDeadLetterParkingRecovery(
                store, queue, new ObservationDeadLetterParkingMetrics(),
                "worker-b", Duration.ofSeconds(30));

        var result = recovery.recover(parked.parkingId());

        assertEquals(ObservationDeadLetterParkingRecovery.Status.RECOVERY_IN_PROGRESS, result.status());
        assertNull(result.entry());
        assertEquals(0, queue.size());
        assertEquals(1, recovery.metrics().recoveryInProgress());
        assertTrue(store.find(parked.parkingId()).isPresent());
    }

    @Test
    void releasesClaimWhenQueueIsFullSoAnotherAttemptCanOwnIt() {
        var store = new InMemoryObservationDeadLetterParkingStore(4);
        var parked = store.park(letter("queue-full"), "test").entry();
        var queue = new BoundedObservationDeadLetterQueue(1,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        assertTrue(queue.offerWithoutEviction(letter("already-queued")));
        var recovery = new ObservationDeadLetterParkingRecovery(
                store, queue, new ObservationDeadLetterParkingMetrics(),
                "worker-a", Duration.ofSeconds(30));

        var result = recovery.recover(parked.parkingId());

        assertEquals(ObservationDeadLetterParkingRecovery.Status.QUEUE_CAPACITY_REACHED, result.status());
        assertEquals(1, queue.size());
        assertTrue(store.claim(parked.parkingId(), "worker-b", Duration.ofSeconds(30)).claimed(),
                "queue-capacity failure must release the recovery lease");
        assertEquals(1, recovery.metrics().recoveryQueueFull());
    }

    @Test
    void stopsBeforeQueueHandoffWhenLeaseRenewalIsRejected() {
        var delegate = new InMemoryObservationDeadLetterParkingStore(4);
        var parked = delegate.park(letter("renewal-rejected"), "test").entry();
        LeasedObservationDeadLetterParkingStore rejectingRenewal =
                (LeasedObservationDeadLetterParkingStore) Proxy.newProxyInstance(
                        LeasedObservationDeadLetterParkingStore.class.getClassLoader(),
                        new Class<?>[]{LeasedObservationDeadLetterParkingStore.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("renew")) return Optional.empty();
                            try {
                                return method.invoke(delegate, args);
                            } catch (java.lang.reflect.InvocationTargetException exception) {
                                throw exception.getCause();
                            }
                        });
        var queue = new BoundedObservationDeadLetterQueue(2,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var recovery = new ObservationDeadLetterParkingRecovery(
                rejectingRenewal, queue, new ObservationDeadLetterParkingMetrics(),
                "worker-a", Duration.ofSeconds(30));

        var result = recovery.recover(parked.parkingId());

        assertEquals(ObservationDeadLetterParkingRecovery.Status.LEASE_LOST, result.status());
        assertEquals(parked, result.entry());
        assertEquals(0, queue.size(), "must not enqueue after losing the lease");
        assertTrue(delegate.find(parked.parkingId()).isPresent(),
                "failed renewal must leave the parked entry available for later recovery");
        assertEquals(1, recovery.metrics().recoveryLeaseLost());
    }

    @Test
    void reportsDuplicateRiskWhenLeaseIsTakenOverBeforeCompletion() {
        var clock = new MutableClock(Instant.parse("2026-11-08T10:00:00Z"));
        var delegate = new InMemoryObservationDeadLetterParkingStore(4, clock);
        var parked = delegate.park(letter("takeover-race"), "test").entry();
        var successorClaim = new AtomicReference<LeasedObservationDeadLetterParkingStore.RecoveryClaim>();
        LeasedObservationDeadLetterParkingStore takeoverDuringCompletion =
                (LeasedObservationDeadLetterParkingStore) Proxy.newProxyInstance(
                        LeasedObservationDeadLetterParkingStore.class.getClassLoader(),
                        new Class<?>[]{LeasedObservationDeadLetterParkingStore.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("complete")) {
                                // Simulate a stalled hand-off: the renewed lease expires,
                                // another worker takes ownership, then the stale worker completes.
                                clock.advance(Duration.ofSeconds(31));
                                var takeover = delegate.claim(
                                        parked.parkingId(), "worker-b", Duration.ofSeconds(30));
                                assertTrue(takeover.claimed());
                                successorClaim.set(takeover.claim());
                            }
                            try {
                                return method.invoke(delegate, args);
                            } catch (java.lang.reflect.InvocationTargetException exception) {
                                throw exception.getCause();
                            }
                        });
        var queue = new BoundedObservationDeadLetterQueue(2,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var recovery = new ObservationDeadLetterParkingRecovery(
                takeoverDuringCompletion, queue, new ObservationDeadLetterParkingMetrics(),
                "worker-a", Duration.ofSeconds(30));

        var result = recovery.recover(parked.parkingId());

        assertEquals(ObservationDeadLetterParkingRecovery.Status.DUPLICATE_POSSIBLE, result.status());
        assertEquals(parked, result.entry());
        assertEquals(1, queue.size(), "the first worker already handed the item to the queue");
        assertTrue(delegate.find(parked.parkingId()).isPresent(),
                "the stale worker must not remove the parked copy owned by the successor");
        assertEquals(1, recovery.metrics().recoveryDuplicatePossible());

        assertEquals(parked, delegate.complete(successorClaim.get()).orElseThrow(),
                "the current owner must still be able to complete the parked entry");
        assertTrue(delegate.find(parked.parkingId()).isEmpty());
    }

    @Test
    void validatesExplicitLeaseConfiguration() {
        var store = new InMemoryObservationDeadLetterParkingStore(1);
        var queue = new BoundedObservationDeadLetterQueue(1,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var metrics = new ObservationDeadLetterParkingMetrics();

        assertThrows(IllegalArgumentException.class,
                () -> new ObservationDeadLetterParkingRecovery(store, queue, metrics, " ", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ObservationDeadLetterParkingRecovery(store, queue, metrics, "worker", Duration.ZERO));
    }

    private static ObservationDeadLetter letter(String id) {
        var event = new ObservationEvent(id, "execution-1", "trace-1", "TEST",
                Instant.parse("2026-11-06T10:00:00Z"), Map.of());
        return new ObservationDeadLetter(event, 1, ObservationDeliveryFailure.UNKNOWN,
                IllegalStateException.class.getName(), Instant.parse("2026-11-06T10:00:01Z"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
