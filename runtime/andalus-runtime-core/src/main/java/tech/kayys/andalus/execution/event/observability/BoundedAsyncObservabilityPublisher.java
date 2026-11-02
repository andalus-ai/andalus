package tech.kayys.andalus.execution.event.observability;

import tech.kayys.andalus.observability.ObservationEvent;
import tech.kayys.andalus.observability.ObservabilityPublisher;
import tech.kayys.andalus.telemetry.Metrics;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded, single-worker observation publisher.
 *
 * <p>Publishing only enqueues and never waits for queue capacity. Overflow is
 * explicit and observable. This is an in-memory best-effort transport; it
 * does not provide crash durability or at-least-once delivery.</p>
 */
public final class BoundedAsyncObservabilityPublisher
        implements ObservabilityPublisher, AutoCloseable {

    public enum OverflowPolicy {
        DROP_OLDEST,
        DROP_NEWEST
    }

    private static final Map<String, String> METRIC_TAGS = Map.of("component", "observability-publisher");

    private final ObservabilityPublisher delegate;
    private final BlockingQueue<ObservationEvent> queue;
    private final OverflowPolicy overflowPolicy;
    private final long shutdownTimeoutMillis;
    private final Metrics metrics;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongAdder accepted = new LongAdder();
    private final LongAdder delivered = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final Thread worker;

    public BoundedAsyncObservabilityPublisher(
            ObservabilityPublisher delegate,
            int capacity,
            OverflowPolicy overflowPolicy,
            Duration shutdownTimeout) {
        this(delegate, capacity, overflowPolicy, shutdownTimeout, null);
    }

    public BoundedAsyncObservabilityPublisher(
            ObservabilityPublisher delegate,
            int capacity,
            OverflowPolicy overflowPolicy,
            Duration shutdownTimeout,
            Metrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be greater than zero");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.overflowPolicy = Objects.requireNonNull(overflowPolicy, "overflowPolicy must not be null");
        this.shutdownTimeoutMillis = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout must not be null")
                .toMillis();
        if (shutdownTimeoutMillis < 0) {
            throw new IllegalArgumentException("shutdownTimeout must not be negative");
        }
        this.metrics = metrics;

        this.worker = new Thread(this::runWorker, "andalus-observability-publisher");
        this.worker.setDaemon(true);
        this.worker.start();
        recordGauge("andalus.observability.publisher.queue.size", 0);
        recordGauge("andalus.observability.publisher.queue.capacity", capacity);
    }

    @Override
    public void publish(ObservationEvent event) {
        if (event == null) {
            return;
        }

        synchronized (lifecycleLock) {
            if (!enabled() || !accepting.get()) {
                return;
            }

            if (queue.offer(event)) {
                accepted.increment();
                recordCounter("andalus.observability.publisher.accepted", 1);
                recordQueueSize();
                return;
            }

            if (overflowPolicy == OverflowPolicy.DROP_NEWEST) {
                recordDropped(1);
                return;
            }

            ObservationEvent evicted = queue.poll();
            if (evicted != null) {
                recordDropped(1);
            }

            if (queue.offer(event)) {
                accepted.increment();
                recordCounter("andalus.observability.publisher.accepted", 1);
            } else {
                recordDropped(1);
            }
            recordQueueSize();
        }
    }

    @Override
    public boolean enabled() {
        return accepting.get() && delegate.enabled();
    }

    public int queueSize() {
        return queue.size();
    }

    public int queueCapacity() {
        return queue.size() + queue.remainingCapacity();
    }

    public long acceptedCount() {
        return accepted.sum();
    }

    public long deliveredCount() {
        return delivered.sum();
    }

    public long droppedCount() {
        return dropped.sum();
    }

    public long failureCount() {
        return failures.sum();
    }

    public boolean isWorkerAlive() {
        return worker.isAlive();
    }

    private void runWorker() {
        while (accepting.get() || !queue.isEmpty()) {
            try {
                ObservationEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
                if (event != null) {
                    recordQueueSize();
                    try {
                        delegate.publish(event);
                        delivered.increment();
                        recordCounter("andalus.observability.publisher.delivered", 1);
                    } catch (RuntimeException failure) {
                        failures.increment();
                        recordCounter("andalus.observability.publisher.delivery.failures", 1);
                    }
                }
            } catch (InterruptedException interrupted) {
                if (accepting.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        recordQueueSize();
    }

    private void recordDropped(long count) {
        dropped.add(count);
        recordCounter("andalus.observability.publisher.dropped", count);
    }

    private void recordQueueSize() {
        recordGauge("andalus.observability.publisher.queue.size", queue.size());
    }

    private void recordCounter(String name, long increment) {
        if (metrics == null) {
            return;
        }
        try {
            metrics.counter(name, increment, METRIC_TAGS);
        } catch (RuntimeException ignored) {
            // Telemetry is strictly best-effort and must never affect delivery.
        }
    }

    private void recordGauge(String name, double value) {
        if (metrics == null) {
            return;
        }
        try {
            metrics.gauge(name, value, METRIC_TAGS);
        } catch (RuntimeException ignored) {
            // Telemetry is strictly best-effort and must never affect delivery.
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            accepting.set(false);
        }
        worker.interrupt();

        try {
            if (shutdownTimeoutMillis > 0) {
                worker.join(shutdownTimeoutMillis);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        if (worker.isAlive()) {
            int abandoned = queue.size();
            queue.clear();
            if (abandoned > 0) {
                recordDropped(abandoned);
            }
            worker.interrupt();
        }
        recordQueueSize();
    }
}
