package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryObservationDeadLetterParkingStoreTest {
    @Test
    void parksAndFindsEntriesInInsertionOrder() {
        var store = new InMemoryObservationDeadLetterParkingStore(3);
        var first = store.park(letter("first"), "manual review");
        var second = store.park(letter("second"), "policy hold");

        assertTrue(first.stored());
        assertTrue(second.stored());
        assertEquals("manual review", first.entry().reason());
        assertEquals(first.entry(), store.find(first.entry().parkingId()).orElseThrow());
        assertEquals(
                java.util.List.of(first.entry(), second.entry()),
                store.list(10));
        assertEquals(2, store.size());
    }

    @Test
    void rejectsAtCapacityWithoutEvictingExistingEntries() {
        var store = new InMemoryObservationDeadLetterParkingStore(1);
        var first = store.park(letter("first"), "keep");
        var rejected = store.park(letter("second"), "also keep");

        assertTrue(first.stored());
        assertEquals(ObservationDeadLetterParkingStore.ParkStatus.CAPACITY_REACHED,
                rejected.status());
        assertNull(rejected.entry());
        assertEquals(first.entry(), store.find(first.entry().parkingId()).orElseThrow());
        assertEquals(1, store.size());
        assertEquals(1, store.metrics().rejected());
    }

    @Test
    void removeReturnsEntryForCallerManagedRecovery() {
        var store = new InMemoryObservationDeadLetterParkingStore(2);
        var parked = store.park(letter("recover"), "retry after review").entry();

        var removed = store.remove(parked.parkingId());

        assertEquals(parked, removed.orElseThrow());
        assertTrue(store.find(parked.parkingId()).isEmpty());
        assertTrue(store.remove(parked.parkingId()).isEmpty());
        assertEquals(1, store.metrics().removed());
        assertEquals(0, store.size());
    }

    @Test
    void listIsBoundedAndImmutable() {
        var store = new InMemoryObservationDeadLetterParkingStore(3);
        store.park(letter("one"), "");
        store.park(letter("two"), "");
        store.park(letter("three"), "");

        var page = store.list(2);

        assertEquals(2, page.size());
        assertThrows(UnsupportedOperationException.class, () -> page.clear());
        assertThrows(IllegalArgumentException.class, () -> store.list(0));
    }

    @Test
    void reportsCapacityAndUtilizationMetrics() {
        var store = new InMemoryObservationDeadLetterParkingStore(4);
        store.park(letter("one"), "");
        store.park(letter("two"), "");
        var metrics = store.metrics();

        assertEquals(2, metrics.parked());
        assertEquals(0, metrics.rejected());
        assertEquals(0, metrics.removed());
        assertEquals(2, metrics.size());
        assertEquals(4, metrics.capacity());
        assertEquals(0.5, metrics.utilization());
    }

    @Test
    void validatesCapacityAndIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryObservationDeadLetterParkingStore(0));
        var store = new InMemoryObservationDeadLetterParkingStore(1);
        assertThrows(IllegalArgumentException.class, () -> store.find(" "));
        assertThrows(IllegalArgumentException.class, () -> store.remove(null));
    }

    private static ObservationDeadLetter letter(String id) {
        var event = new ObservationEvent(id, "execution-1", "trace-1", "TEST",
                Instant.parse("2026-11-05T00:00:00Z"), Map.of());
        return new ObservationDeadLetter(event, 1, ObservationDeliveryFailure.UNKNOWN,
                IllegalStateException.class.getName(), Instant.parse("2026-11-05T00:00:01Z"));
    }
}
