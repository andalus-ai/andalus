package tech.kayys.andalus.coordination.discovery;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import jakarta.enterprise.context.ApplicationScoped;

/** Low-cardinality in-process registration counters and timing aggregates. */
@ApplicationScoped
public class AgentRuntimeRegistrationTelemetry implements AgentRuntimeRegistrationObserver {
    private final Map<Event, LongAdder> counters = new EnumMap<>(Event.class);
    private final AtomicLong lastSuccessEpochMillis = new AtomicLong();
    private final AtomicLong lastFailureEpochMillis = new AtomicLong();
    private final LongAdder renewalCount = new LongAdder();
    private final LongAdder totalRenewalNanos = new LongAdder();
    private final AtomicLong maxRenewalNanos = new AtomicLong();

    public AgentRuntimeRegistrationTelemetry() {
        for (Event event : Event.values()) counters.put(event, new LongAdder());
    }

    @Override
    public void onEvent(Event event) {
        counters.get(event).increment();
        long now = Instant.now().toEpochMilli();
        switch (event) {
            case ACQUIRED, AVAILABLE, UNAVAILABLE, RENEWED, REVOKED ->
                    lastSuccessEpochMillis.set(now);
            case RENEWAL_REJECTED, STATUS_REJECTED, RENEWAL_ERROR,
                    REVOCATION_REJECTED, OPERATION_ERROR, SCHEDULING_ERROR ->
                    lastFailureEpochMillis.set(now);
        }
    }

    @Override
    public void onRenewalDuration(long elapsedNanos) {
        if (elapsedNanos < 0) return;
        renewalCount.increment();
        totalRenewalNanos.add(elapsedNanos);
        maxRenewalNanos.accumulateAndGet(elapsedNanos, Math::max);
    }

    public Snapshot snapshot() {
        Map<Event, Long> values = new EnumMap<>(Event.class);
        counters.forEach((event, count) -> values.put(event, count.sum()));
        return new Snapshot(Map.copyOf(values), lastSuccessEpochMillis.get(),
                lastFailureEpochMillis.get(), renewalCount.sum(),
                totalRenewalNanos.sum(), maxRenewalNanos.get());
    }

    public record Snapshot(Map<Event, Long> counts, long lastSuccessEpochMillis,
            long lastFailureEpochMillis, long renewalCount, long totalRenewalNanos,
            long maxRenewalNanos) {
        public long count(Event event) { return counts.getOrDefault(event, 0L); }
        public Instant lastSuccess() {
            return lastSuccessEpochMillis == 0 ? null : Instant.ofEpochMilli(lastSuccessEpochMillis);
        }
        public Instant lastFailure() {
            return lastFailureEpochMillis == 0 ? null : Instant.ofEpochMilli(lastFailureEpochMillis);
        }
        public long averageRenewalNanos() {
            return renewalCount == 0 ? 0 : totalRenewalNanos / renewalCount;
        }
    }
}
