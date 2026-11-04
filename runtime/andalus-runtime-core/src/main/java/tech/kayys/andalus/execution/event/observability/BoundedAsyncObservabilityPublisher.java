package tech.kayys.andalus.execution.event.observability;

import tech.kayys.andalus.observability.ObservationEvent;
import tech.kayys.andalus.observability.ObservabilityPublisher;
import tech.kayys.andalus.telemetry.Metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded, single-worker, best-effort observation publisher.
 * Enqueue is nonblocking; delivery retries and dead-letter retention are bounded and in-memory.
 */
public final class BoundedAsyncObservabilityPublisher implements ObservabilityPublisher, AutoCloseable {
    public enum OverflowPolicy { DROP_OLDEST, DROP_NEWEST }

    private static final Map<String, String> METRIC_TAGS = Map.of("component", "observability-publisher");
    private final ObservabilityPublisher delegate;
    private final BlockingQueue<ObservationEvent> queue;
    private final OverflowPolicy overflowPolicy;
    private final long shutdownTimeoutMillis;
    private final Metrics metrics;
    private final ObservationDeliveryRetryPolicy retryPolicy;
    private final BoundedObservationDeadLetterQueue deadLetterQueue;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongAdder accepted = new LongAdder(), delivered = new LongAdder();
    private final LongAdder dropped = new LongAdder(), failures = new LongAdder(), attempts = new LongAdder();
    private final LongAdder deadLettered = new LongAdder();
    private final Thread worker;

    public BoundedAsyncObservabilityPublisher(ObservabilityPublisher delegate, int capacity,
            OverflowPolicy overflowPolicy, Duration shutdownTimeout) {
        this(delegate, capacity, overflowPolicy, shutdownTimeout, null);
    }

    public BoundedAsyncObservabilityPublisher(ObservabilityPublisher delegate, int capacity,
            OverflowPolicy overflowPolicy, Duration shutdownTimeout, Metrics metrics) {
        this(delegate, capacity, overflowPolicy, shutdownTimeout, metrics, ObservationDeliveryRetryPolicy.noRetries());
    }

    public BoundedAsyncObservabilityPublisher(ObservabilityPublisher delegate, int capacity,
            OverflowPolicy overflowPolicy, Duration shutdownTimeout, Metrics metrics,
            ObservationDeliveryRetryPolicy retryPolicy) {
        this(delegate, capacity, overflowPolicy, shutdownTimeout, metrics, retryPolicy, null);
    }

    /** Enables retry-exhaustion/permanent-failure routing to a caller-owned bounded DLQ. */
    public BoundedAsyncObservabilityPublisher(ObservabilityPublisher delegate, int capacity,
            OverflowPolicy overflowPolicy, Duration shutdownTimeout, Metrics metrics,
            ObservationDeliveryRetryPolicy retryPolicy,
            BoundedObservationDeadLetterQueue deadLetterQueue) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        if (capacity < 1) throw new IllegalArgumentException("capacity must be greater than zero");
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.overflowPolicy = Objects.requireNonNull(overflowPolicy, "overflowPolicy must not be null");
        this.shutdownTimeoutMillis = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout must not be null").toMillis();
        if (shutdownTimeoutMillis < 0) throw new IllegalArgumentException("shutdownTimeout must not be negative");
        this.metrics = metrics;
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        this.deadLetterQueue = deadLetterQueue;
        this.worker = new Thread(this::runWorker, "andalus-observability-publisher");
        worker.setDaemon(true);
        worker.start();
        recordGauge("andalus.observability.publisher.queue.size", 0);
        recordGauge("andalus.observability.publisher.queue.capacity", capacity);
    }

    @Override public void publish(ObservationEvent event) {
        if (event == null) return;
        synchronized (lifecycleLock) {
            if (!enabled() || !accepting.get()) return;
            if (queue.offer(event)) {
                accepted.increment(); recordCounter("andalus.observability.publisher.accepted", 1); recordQueueSize(); return;
            }
            if (overflowPolicy == OverflowPolicy.DROP_NEWEST) { recordDropped(1); return; }
            if (queue.poll() != null) recordDropped(1);
            if (queue.offer(event)) { accepted.increment(); recordCounter("andalus.observability.publisher.accepted", 1); }
            else recordDropped(1);
            recordQueueSize();
        }
    }

    @Override public boolean enabled() { return accepting.get() && delegate.enabled(); }
    public int queueSize() { return queue.size(); }
    public int queueCapacity() { return queue.size() + queue.remainingCapacity(); }
    public long acceptedCount() { return accepted.sum(); }
    public long deliveredCount() { return delivered.sum(); }
    public long droppedCount() { return dropped.sum(); }
    /** Number of delegate invocations, including failed attempts. */
    public long deliveryAttemptCount() { return attempts.sum(); }
    /** Number of observations that exhausted retries or failed terminally. */
    public long failureCount() { return failures.sum(); }
    public long deadLetteredCount() { return deadLettered.sum(); }
    public boolean isWorkerAlive() { return worker.isAlive(); }

    private void runWorker() {
        while (accepting.get() || !queue.isEmpty()) {
            try {
                ObservationEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
                if (event == null) continue;
                recordQueueSize();
                deliver(event);
            } catch (InterruptedException interrupted) {
                if (accepting.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        recordQueueSize();
    }

    private void deliver(ObservationEvent event) {
        RuntimeException terminalFailure = null;
        int attemptsMade = 0;
        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            if (attempt > 1 && !waitForRetry(retryPolicy.backoffBeforeAttempt(attempt), event)) return;
            attempts.increment();
            attemptsMade = attempt;
            try {
                delegate.publish(event);
                delivered.increment();
                recordCounter("andalus.observability.publisher.delivered", 1);
                return;
            } catch (RuntimeException failure) {
                terminalFailure = failure;
                ObservationDeliveryFailure classification = ObservationDeliveryFailure.classify(failure);
                if (classification == ObservationDeliveryFailure.PERMANENT || attempt == retryPolicy.maxAttempts()) {
                    failures.increment();
                    recordCounter("andalus.observability.publisher.delivery.failures", 1);
                    routeDeadLetter(event, attemptsMade, classification, failure);
                    return;
                }
            }
        }
    }

    private void routeDeadLetter(ObservationEvent event, int attemptsMade,
            ObservationDeliveryFailure classification, RuntimeException failure) {
        if (deadLetterQueue == null) return;
        ObservationDeadLetter letter = new ObservationDeadLetter(event, attemptsMade, classification,
                failure.getClass().getName(), Instant.now());
        if (deadLetterQueue.offer(letter)) {
            deadLettered.increment();
            recordCounter("andalus.observability.publisher.dead-lettered", 1);
        } else {
            recordCounter("andalus.observability.publisher.dead-letter.dropped", 1);
        }
    }

    private boolean waitForRetry(Duration delay, ObservationEvent event) {
        if (delay.isZero()) return true;
        try {
            long millis = delay.toMillis();
            int nanos = (int) delay.minusMillis(millis).toNanos();
            Thread.sleep(millis, nanos);
            return true;
        } catch (InterruptedException interrupted) {
            if (!accepting.get()) {
                recordDropped(1);
                return false;
            }
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void recordDropped(long count) { dropped.add(count); recordCounter("andalus.observability.publisher.dropped", count); }
    private void recordQueueSize() { recordGauge("andalus.observability.publisher.queue.size", queue.size()); }
    private void recordCounter(String name, long increment) {
        if (metrics == null) return;
        try { metrics.counter(name, increment, METRIC_TAGS); } catch (RuntimeException ignored) { }
    }
    private void recordGauge(String name, double value) {
        if (metrics == null) return;
        try { metrics.gauge(name, value, METRIC_TAGS); } catch (RuntimeException ignored) { }
    }

    @Override public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return;
            accepting.set(false);
        }
        worker.interrupt();
        try { if (shutdownTimeoutMillis > 0) worker.join(shutdownTimeoutMillis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (worker.isAlive()) {
            int abandoned = queue.size(); queue.clear();
            if (abandoned > 0) recordDropped(abandoned);
            worker.interrupt();
        }
        recordQueueSize();
    }
}