package tech.kayys.andalus.execution.event.observability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import tech.kayys.andalus.harness.fabric.ExecutionLeaseObservation;
import tech.kayys.andalus.harness.fabric.ExecutionLeaseObserver;
import tech.kayys.andalus.telemetry.Metrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime adapter translating framework lease observations into bounded-cardinality
 * metrics. Execution IDs and fencing generations are deliberately never metric tags.
 */
@ApplicationScoped
public class ExecutionLeaseMetricsObserver implements ExecutionLeaseObserver {
    private static final String PREFIX = "andalus.execution.lease.";
    private static final Map<String, String> COMPONENT_TAG = Map.of("component", "execution-lease");
    private final AtomicLong activeLeases = new AtomicLong();
    private final AtomicLong ownershipLosses = new AtomicLong();
    private final AtomicLong renewalSuccesses = new AtomicLong();
    private final AtomicLong renewalFailures = new AtomicLong();
    private final AtomicLong renewalRejections = new AtomicLong();
    private final AtomicLong lastRenewalSuccessEpochMillis = new AtomicLong();
    private final AtomicLong lastHeartbeatObservedEpochMillis = new AtomicLong();
    private final Clock clock;
    private final Instance<Metrics> metrics;

    @Inject
    public ExecutionLeaseMetricsObserver(Instance<Metrics> metrics) {
        this(metrics, Clock.systemUTC());
    }

    ExecutionLeaseMetricsObserver(Instance<Metrics> metrics, Clock clock) {
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public void onObservation(ExecutionLeaseObservation observation) {
        if (observation == null) {
            return;
        }
        long now = clock.millis();
        switch (observation.type()) {
            case ACQUIRED -> activeLeases.incrementAndGet();
            case RELEASED -> activeLeases.updateAndGet(value -> Math.max(0, value - 1));
            case RENEWAL_SUCCEEDED -> {
                renewalSuccesses.incrementAndGet();
                lastRenewalSuccessEpochMillis.set(now);
                lastHeartbeatObservedEpochMillis.set(now);
                recordCounter("renewals", "outcome", "success");
                if (observation.renewalDuration() != null) {
                    recordHistogram("renewal.duration", observation.renewalDuration().toNanos() / 1_000_000.0,
                            Map.of("outcome", "success"));
                }
            }
            case RENEWAL_REJECTED -> {
                renewalRejections.incrementAndGet();
                lastHeartbeatObservedEpochMillis.set(now);
                recordCounter("renewals", "outcome", "rejected");
                recordCounter("ownership.loss", "reason", "renewal_rejected");
            }
            case RENEWAL_FAILED -> {
                renewalFailures.incrementAndGet();
                lastHeartbeatObservedEpochMillis.set(now);
                recordCounter("renewals", "outcome", "failure");
                recordCounter("ownership.loss", "reason", "renewal_failed");
                if (observation.renewalDuration() != null) {
                    recordHistogram("renewal.duration", observation.renewalDuration().toNanos() / 1_000_000.0,
                            Map.of("outcome", "failure"));
                }
            }
            case OWNERSHIP_LOST -> {
                ownershipLosses.incrementAndGet();
                lastHeartbeatObservedEpochMillis.set(now);
                recordCounter("ownership.loss", "reason", "fenced_or_expired");
            }
        }
        recordGauge("active", activeLeases.get());
        recordGauge("ownership.losses.total", ownershipLosses.get());
    }

    public long activeLeaseCount() { return activeLeases.get(); }
    public long ownershipLossCount() { return ownershipLosses.get(); }
    public long renewalSuccessCount() { return renewalSuccesses.get(); }
    public long renewalFailureCount() { return renewalFailures.get(); }
    public long renewalRejectionCount() { return renewalRejections.get(); }

    /** Returns true only when a worker that has emitted heartbeats is still reporting them. */
    public boolean heartbeatFresh(Duration staleAfter) {
        if (activeLeases.get() == 0 || lastRenewalSuccessEpochMillis.get() == 0) {
            return true;
        }
        long last = lastHeartbeatObservedEpochMillis.get();
        return last > 0 && Duration.between(Instant.ofEpochMilli(last), clock.instant())
                .compareTo(staleAfter) <= 0;
    }

    public long millisSinceLastHeartbeat() {
        long last = lastHeartbeatObservedEpochMillis.get();
        return last == 0 ? -1 : Math.max(0, clock.millis() - last);
    }

    private void recordCounter(String metric, String tag, String value) {
        withMetrics(m -> m.counter(PREFIX + metric, 1, Map.of(tag, value)));
    }

    private void recordHistogram(String metric, double value, Map<String, String> tags) {
        withMetrics(m -> m.histogram(PREFIX + metric, value, tags));
    }

    private void recordGauge(String metric, double value) {
        withMetrics(m -> m.gauge(PREFIX + metric, value, COMPONENT_TAG));
    }

    private void withMetrics(java.util.function.Consumer<Metrics> action) {
        try {
            if (metrics != null && metrics.isResolvable()) {
                action.accept(metrics.get());
            }
        } catch (RuntimeException ignored) {
            // Metrics backends are diagnostic and must never affect lease ownership.
        }
    }
}
