package tech.kayys.andalus.execution.event.observability;

import tech.kayys.andalus.observability.ObservationEvent;
import tech.kayys.andalus.observability.ObservabilityPublisher;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded, single-worker observation publisher.
 *
 * <p>Publishing only enqueues and never waits for queue capacity. The single
 * worker preserves the order in which events are dequeued. Overflow is
 * explicit and observable: DROP_OLDEST retains the freshest backlog, while
 * DROP_NEWEST preserves already accepted work.</p>
 *
 * <p>This is intentionally an in-memory best-effort transport. It does not
 * provide crash durability or at-least-once delivery.</p>
 */
public final class BoundedAsyncObservabilityPublisher
        implements ObservabilityPublisher, AutoCloseable {

    public enum OverflowPolicy {
        DROP_OLDEST,
        DROP_NEWEST
    }

    private final ObservabilityPublisher delegate;
    private final BlockingQueue<ObservationEvent> queue;
    private final OverflowPolicy overflowPolicy;
    private final long shutdownTimeoutMillis;
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

        this.worker = new Thread(this::runWorker, "andalus-observability-publisher");
        this.worker.setDaemon(true);
        this.worker.start();
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
                return;
            }

            if (overflowPolicy == OverflowPolicy.DROP_NEWEST) {
                dropped.increment();
                return;
            }

            // DROP_OLDEST: remove one queued event and retry once. The worker
            // may concurrently free capacity; a failed retry is counted as loss.
            ObservationEvent evicted = queue.poll();
            if (evicted != null) {
                dropped.increment();
            }

            if (queue.offer(event)) {
                accepted.increment();
            } else {
                dropped.increment();
            }
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
                    try {
                        delegate.publish(event);
                        delivered.increment();
                    } catch (RuntimeException failure) {
                        failures.increment();
                    }
                }
            } catch (InterruptedException interrupted) {
                // close() interrupts only to wake the poll; queued work is drained
                // until the configured shutdown deadline expires.
                if (accepting.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
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
                dropped.add(abandoned);
            }
            worker.interrupt();
        }
    }
}
