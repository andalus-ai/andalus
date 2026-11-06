package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ObservationDeadLetterParkingRecoveryDuplicatePossibleTest {
    @Test
    void reportsDuplicatePossibleWhenQueueAcceptsButStoreRemovalCannotConfirm() {
        var store = new RemovalUnconfirmedStore(2);
        var parked = store.park(letter("recover"), "test").entry();
        var queue = new BoundedObservationDeadLetterQueue(2,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        var recovery = new ObservationDeadLetterParkingRecovery(store, queue);

        var result = recovery.recover(parked.parkingId());

        assertEquals(ObservationDeadLetterParkingRecovery.Status.DUPLICATE_POSSIBLE, result.status());
        assertEquals(parked, result.entry());
        assertEquals(1, queue.size());
        assertEquals(1, store.size());
        assertTrue(store.find(parked.parkingId()).isPresent());
        assertEquals(1, recovery.metrics().recoveryDuplicatePossible());
        assertEquals(0, recovery.metrics().recoverySucceeded());
        assertEquals(0, store.metrics().removed());
    }

    private static ObservationDeadLetter letter(String id) {
        var event = new ObservationEvent(id, "execution-1", "trace-1", "TEST",
                Instant.parse("2026-11-06T00:00:00Z"), Map.of());
        return new ObservationDeadLetter(event, 1, ObservationDeliveryFailure.UNKNOWN,
                IllegalStateException.class.getName(), Instant.parse("2026-11-06T00:00:01Z"));
    }

    /**
     * Simulates a store whose lookup succeeds but whose remove operation cannot
     * confirm deletion. The entry remains parked, so queue-first recovery may
     * have reactivated a duplicate and must report that ambiguity.
     */
    private static final class RemovalUnconfirmedStore implements ObservationDeadLetterParkingStore {
        private final InMemoryObservationDeadLetterParkingStore delegate;

        private RemovalUnconfirmedStore(int capacity) {
            this.delegate = new InMemoryObservationDeadLetterParkingStore(capacity);
        }

        @Override
        public ParkingResult park(ObservationDeadLetter deadLetter, String reason) {
            return delegate.park(deadLetter, reason);
        }

        @Override
        public Optional<ParkedObservationDeadLetter> find(String parkingId) {
            return delegate.find(parkingId);
        }

        @Override
        public List<ParkedObservationDeadLetter> list(int limit) {
            return delegate.list(limit);
        }

        @Override
        public Optional<ParkedObservationDeadLetter> remove(String parkingId) {
            return Optional.empty();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public int capacity() {
            return delegate.capacity();
        }

        @Override
        public ParkingMetrics metrics() {
            return delegate.metrics();
        }
    }
}
