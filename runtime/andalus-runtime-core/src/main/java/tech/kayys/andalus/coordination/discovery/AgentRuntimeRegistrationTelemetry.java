package tech.kayys.andalus.coordination.discovery;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-process aggregate registration telemetry. Counters intentionally have no
 * tenant, agent, instance, or owner labels to avoid unbounded cardinality and
 * accidental identity leakage.
 *
 * <p>Export the snapshot through the embedding application's metrics system;
 * this class does not introduce a metrics vendor or exporter dependency.</p>
 */
@ApplicationScoped
public class AgentRuntimeRegistrationTelemetry implements AgentRuntimeRegistrationObserver {

    private final Map<Event, LongAdder> counters = new EnumMap<>(Event.class);
    private final AtomicLong lastSuccessEpochMillis = new AtomicLong();
    private final AtomicLong lastFailureEpochMillis = new AtomicLong();

    public AgentRuntimeRegistrationTelemetry() {
        for (Event event : Event.values()) {
            counters.put(event, new LongAdder());
        }
    }

    @Override
    public void onEvent(Event event) {
        counters.get(event).increment();
        long now = Instant.now().toEpochMilli();
        switch (event) {
            case ACQUIRED, AVAILABLE, UNAVAILABLE, RENEWED, REVOKED ->
                    lastSuccessEpochMillis.set(now);
            case RENEWAL_REJECTED, RENEWAL_ERROR, REVOCATION_REJECTED,
                    OPERATION_ERROR, SCHEDULING_ERROR -> lastFailureEpochMillis.set(now);
        }
    }

    /** Immutable point-in-time aggregate suitable for a host metrics exporter. */
    public Snapshot snapshot() {
        Map<Event, Long> values = new EnumMap<>(Event.class);
        counters.forEach((event, count) -> values.put(event, count.sum()));
        return new Snapshot(Map.copyOf(values),
                lastSuccessEpochMillis.get(), lastFailureEpochMillis.get());
    }

    public record Snapshot(
            Map<Event, Long> counts,
            long lastSuccessEpochMillis,
            long lastFailureEpochMillis) {

        public long count(Event event) {
            return counts.getOrDefault(event, 0L);
        }

        public Instant lastSuccess() {
            return lastSuccessEpochMillis == 0 ? null : Instant.ofEpochMilli(lastSuccessEpochMillis);
        }

        public Instant lastFailure() {
            return lastFailureEpochMillis == 0 ? null : Instant.ofEpochMilli(lastFailureEpochMillis);
        }
    }
}
