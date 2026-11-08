package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reusable behavioral contract for every leased parking-store adapter.
 *
 * <p>Backend adapter tests should subclass this type and provide a fresh store
 * using the supplied clock. The suite intentionally tests observable semantics,
 * not implementation details or persistence technology.</p>
 */
abstract class LeasedObservationDeadLetterParkingStoreContract {
    protected abstract LeasedObservationDeadLetterParkingStore newStore(Clock clock);

    @Test
    void onlyOneLiveOwnerCanClaimAnEntry() {
        var clock = new MutableClock(Instant.parse("2026-11-08T10:00:00Z"));
        var store = newStore(clock);
        var parked = park(store);

        var first = store.claim(parked.parkingId(), "worker-a", Duration.ofSeconds(30));
        var competing = store.claim(parked.parkingId(), "worker-b", Duration.ofSeconds(30));

        assertTrue(first.claimed());
        assertEquals(LeasedObservationDeadLetterParkingStore.ClaimStatus.ALREADY_CLAIMED,
                competing.status());
        assertNull(competing.claim());
    }

    @Test
    void renewalPreservesFencingTokenAndExtendsLease() {
        var clock = new MutableClock(Instant.parse("2026-11-08T10:00:00Z"));
        var store = newStore(clock);
        var parked = park(store);
        var claim = store.claim(parked.parkingId(), "worker-a", Duration.ofSeconds(30)).claim();

        clock.advance(Duration.ofSeconds(10));
        var renewed = store.renew(claim, Duration.ofSeconds(45)).orElseThrow();

        assertEquals(claim.token(), renewed.token(), "renewal must not change ownership identity");
        assertEquals(claim.owner(), renewed.owner());
        assertEquals(clock.instant().plusSeconds(45), renewed.leaseUntil());
        assertTrue(store.complete(claim).isEmpty(), "the pre-renewal lease value must not be accepted");
        assertEquals(parked, store.complete(renewed).orElseThrow());
    }

    @Test
    void expiredOwnerCannotMutateAfterSuccessorTakesOver() {
        var clock = new MutableClock(Instant.parse("2026-11-08T10:00:00Z"));
        var store = newStore(clock);
        var parked = park(store);
        var stale = store.claim(parked.parkingId(), "worker-a", Duration.ofSeconds(5)).claim();

        clock.advance(Duration.ofSeconds(6));
        var successor = store.claim(parked.parkingId(), "worker-b", Duration.ofSeconds(30)).claim();

        assertNotNull(successor);
        assertNotEquals(stale.token(), successor.token());
        assertTrue(store.renew(stale, Duration.ofSeconds(30)).isEmpty());
        assertTrue(store.complete(stale).isEmpty());
        assertFalse(store.release(stale));
        assertEquals(parked, store.find(parked.parkingId()).orElseThrow());
        assertEquals(parked, store.complete(successor).orElseThrow());
    }

    @Test
    void removalInvalidatesOutstandingLease() {
        var store = newStore(new MutableClock(Instant.parse("2026-11-08T10:00:00Z")));
        var parked = park(store);
        var claim = store.claim(parked.parkingId(), "worker-a", Duration.ofSeconds(30)).claim();

        assertEquals(parked, store.remove(parked.parkingId()).orElseThrow());
        assertTrue(store.renew(claim, Duration.ofSeconds(30)).isEmpty());
        assertTrue(store.complete(claim).isEmpty());
        assertFalse(store.release(claim));
    }

    private static ParkedObservationDeadLetter park(LeasedObservationDeadLetterParkingStore store) {
        var event = new ObservationEvent("contract-event", "execution-contract", "trace-contract",
                "CONTRACT_TEST", Instant.parse("2026-11-08T09:59:00Z"), Map.of());
        var deadLetter = new ObservationDeadLetter(event, 1, ObservationDeliveryFailure.UNKNOWN,
                IllegalStateException.class.getName(), Instant.parse("2026-11-08T09:59:01Z"));
        return store.park(deadLetter, "contract").entry();
    }

    protected static final class MutableClock extends Clock {
        private Instant instant;

        protected MutableClock(Instant instant) {
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
