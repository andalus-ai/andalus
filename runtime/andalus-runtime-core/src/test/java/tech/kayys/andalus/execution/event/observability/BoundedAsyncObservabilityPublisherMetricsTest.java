package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;
import tech.kayys.andalus.telemetry.MetricValue;
import tech.kayys.andalus.telemetry.Metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedAsyncObservabilityPublisherMetricsTest {

    @Test
    void recordsPublisherCountersAndQueueGaugeWithoutExecutionTags() {
        RecordingMetrics metrics = new RecordingMetrics();
        var publisher = new BoundedAsyncObservabilityPublisher(
                event -> { }, 4,
                BoundedAsyncObservabilityPublisher.OverflowPolicy.DROP_NEWEST,
                Duration.ofSeconds(2), metrics);

        publisher.publish(event("event-1"));
        publisher.publish(event("event-2"));
        publisher.close();

        assertEquals(2L, metrics.counters.get("andalus.observability.publisher.accepted"));
        assertEquals(2L, metrics.counters.get("andalus.observability.publisher.delivered"));
        assertEquals(0.0, metrics.gauges.get("andalus.observability.publisher.queue.size"));
        assertEquals(Map.of("component", "observability-publisher"),
                metrics.lastTags.get("andalus.observability.publisher.delivered"));
    }

    @Test
    void telemetryFailureNeverBreaksObservationPublishing() {
        Metrics failingMetrics = new RecordingMetrics() {
            @Override
            public void counter(String name, long increment, Map<String, String> tags) {
                throw new IllegalStateException("metrics backend unavailable");
            }

            @Override
            public void gauge(String name, double value, Map<String, String> tags) {
                throw new IllegalStateException("metrics backend unavailable");
            }
        };
        var publisher = new BoundedAsyncObservabilityPublisher(
                event -> { }, 4,
                BoundedAsyncObservabilityPublisher.OverflowPolicy.DROP_NEWEST,
                Duration.ofSeconds(2), failingMetrics);

        publisher.publish(event("event-1"));
        publisher.close();

        assertEquals(1L, publisher.deliveredCount());
        assertEquals(0L, publisher.failureCount());
        assertTrue(publisher.queueSize() >= 0);
    }

    private static ObservationEvent event(String id) {
        return new ObservationEvent(id, "execution-1", "trace-1", "TEST",
                Instant.parse("2026-10-30T00:00:00Z"), Map.of());
    }

    private static class RecordingMetrics implements Metrics {
        final Map<String, Long> counters = new ConcurrentHashMap<>();
        final Map<String, Double> gauges = new ConcurrentHashMap<>();
        final Map<String, Map<String, String>> lastTags = new ConcurrentHashMap<>();

        @Override public void counter(String name, long increment) {
            counters.merge(name, increment, Long::sum);
        }
        @Override public void counter(String name, long increment, Map<String, String> tags) {
            counters.merge(name, increment, Long::sum);
            lastTags.put(name, Map.copyOf(tags));
        }
        @Override public void gauge(String name, double value) { gauges.put(name, value); }
        @Override public void gauge(String name, double value, Map<String, String> tags) {
            gauges.put(name, value);
            lastTags.put(name, Map.copyOf(tags));
        }
        @Override public void histogram(String name, double value) { }
        @Override public void histogram(String name, double value, Map<String, String> tags) { }
        @Override public void timer(String name, long durationMs) { }
        @Override public void timer(String name, long durationMs, Map<String, String> tags) { }
        @Override public Map<String, MetricValue> snapshot() { return Map.of(); }
        @Override public void reset() { counters.clear(); gauges.clear(); lastTags.clear(); }
    }
}
