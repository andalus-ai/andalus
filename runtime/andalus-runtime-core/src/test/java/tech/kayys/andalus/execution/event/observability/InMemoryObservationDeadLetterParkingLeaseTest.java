package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryObservationDeadLetterParkingLeaseTest {
    @Test
    void grantsOnlyOneUnexpiredClaimForAnEntry() {
        var clock = new MutableClock(Instant.parse("2026-11-06T10:00:00Z"));
        var store = new InMemoryObservationDeadLetterParkingStore(2, clock);
        var parked = store.park(letter("lease"), "test").entry();

        var first = store.claim(parked.parkingId(), "worker-a", Duration.ofSeconds(30));
        var second = store.claim(parked.parkingId(), "worker-b", Duration.ofSeconds(30));

        assertEquals(LeasedObservationDeadLetterParkingStore.ClaimStatus.CLAIMED, first.status());
        assertEquals(LeasedObservationDeadLetterParkingStore.ClaimStatus.ALREADY_CLAIMED, second.status());
        assertNull(second.claim(), "a competing worker must not receive the active fencing token");
    }

    @Test
    void expiredLeaseCanBeTakenOverAndOldTokenCannotCompleteOrRelease() {
        var clock = new MutableClock(Instant.parse("2026-11-06T10:00:00Z"));
        var store = new InMemoryObservationDeadLetterParkingStore(2, clock);
        var parked = store.park(letter("lease"), "test").entry();
        var first = store.claim(parked.parkingId(), "worker-a", Duration.ofSeconds(5)).claim();

        clock.advance(Duration.ofSeconds(6));
        var second = store.claim(parked.parkingId(), "worker-b", Duration.ofSeconds(30)).claim();

        assertNotEquals(first.token(), second.token());
        assertTrue(store.complete(first).isEmpty(), "expired owner must not complete after lease takeover");
        assertFalse(store.release(first), "expired owner must not release a newer claim");
        assertEquals(parked, store.complete(second).orElseThrow());
        assertTrue(store.find(parked.parkingId()).isEmpty());
        assertEquals(1, store.metrics().removed());
    }

    @Test
    void reportsMissingEntriesAndRejectsInvalidLeaseRequests() {
        var store = new InMemoryObservationDeadLetterParkingStore(1);
        assertEquals(LeasedObservationDeadLetterParkingStore.ClaimStatus.NOT_FOUND,
                store.claim("missing", "worker", Duration.ofSeconds(1)).status());
        assertThrows(IllegalArgumentException.class,
                () -> store.claim("missing", " ", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> store.claim("missing", "worker", Duration.ZERO));
    }

    private static ObservationDeadLetter letter(String id) {
        var event = new ObservationEvent(id, "execution-1", "trace-1", "TEST",
                Instant.parse("2026-11-06T10:00:00Z"), Map.of());
        return new ObservationDeadLetter(event, 1, ObservationDeliveryFailure.UNKNOWN,
                IllegalStateException.class.getName(), Instant.parse("2026-11-06T10:00:01Z"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
