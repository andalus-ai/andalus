package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Concurrency-focused tests for the leased parking-store implementation. */
class LeasedObservationDeadLetterParkingStoreConcurrencyTest {

    @Test
    void simultaneousClaimsGrantExactlyOneLiveOwner() throws Exception {
        var store = new InMemoryObservationDeadLetterParkingStore(4,
                Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), java.time.ZoneOffset.UTC));
        var parked = park(store);
        int workers = 24;
        var ready = new CountDownLatch(workers);
        var start = new CountDownLatch(1);
        var claimed = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                final int worker = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    await(start);
                    var result = store.claim(parked.parkingId(), "worker-" + worker,
                            Duration.ofSeconds(30));
                    if (result.claimed()) claimed.incrementAndGet();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "workers did not become ready");
            start.countDown();
            for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
            assertEquals(1, claimed.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void simultaneousRenewalsFenceTheLosingClaimHandle() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-11-09T00:00:00Z"));
        var store = new InMemoryObservationDeadLetterParkingStore(4, clock);
        var parked = park(store);
        var original = store.claim(parked.parkingId(), "worker", Duration.ofSeconds(30)).claim();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<LeasedObservationDeadLetterParkingStore.RecoveryClaim> first =
                    pool.submit(() -> renew(store, original, Duration.ofSeconds(40), ready, start));
            Future<LeasedObservationDeadLetterParkingStore.RecoveryClaim> second =
                    pool.submit(() -> renew(store, original, Duration.ofSeconds(50), ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var result1 = first.get(5, TimeUnit.SECONDS);
            var result2 = second.get(5, TimeUnit.SECONDS);

            assertTrue(result1 == null ^ result2 == null,
                    "exactly one renewal of the same lease version should win");
            assertTrue(store.complete(original).isEmpty(),
                    "renewal must fence the original claim");
            var current = result1 != null ? result1 : result2;
            assertEquals(parked, store.complete(current).orElseThrow());
            assertTrue(store.find(parked.parkingId()).isEmpty());
        } finally {
            pool.shutdownNow();
        }
    }

    private static LeasedObservationDeadLetterParkingStore.RecoveryClaim renew(
            LeasedObservationDeadLetterParkingStore store,
            LeasedObservationDeadLetterParkingStore.RecoveryClaim claim,
            Duration duration, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        return store.renew(claim, duration).orElse(null);
    }

    private static ParkedObservationDeadLetter park(LeasedObservationDeadLetterParkingStore store) {
        var event = new ObservationEvent("concurrency-event", "execution-concurrency", "trace-concurrency",
                "CONCURRENCY_TEST", Instant.parse("2026-11-09T00:00:00Z"), Map.of());
        var deadLetter = new ObservationDeadLetter(event, 1, ObservationDeliveryFailure.UNKNOWN,
                IllegalStateException.class.getName(), Instant.parse("2026-11-09T00:00:01Z"));
        return store.park(deadLetter, "concurrency-contract").entry();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("start gate timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", e);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
