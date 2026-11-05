package tech.kayys.andalus.execution.event.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded in-memory dead-letter buffer. It is deliberately not durable and does
 * not perform callbacks on the observation delivery worker.
 */
public final class BoundedObservationDeadLetterQueue {
    public enum OverflowPolicy { DROP_OLDEST, DROP_NEWEST }

    private final ArrayBlockingQueue<ObservationDeadLetter> queue;
    private final OverflowPolicy overflowPolicy;
    private final LongAdder accepted = new LongAdder();
    private final LongAdder dropped = new LongAdder();

    public BoundedObservationDeadLetterQueue(int capacity, OverflowPolicy overflowPolicy) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be greater than zero");
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.overflowPolicy = Objects.requireNonNull(overflowPolicy, "overflowPolicy must not be null");
    }

    /** Nonblocking; returns true when retained, false when this letter was dropped. */
    public boolean offer(ObservationDeadLetter deadLetter) {
        Objects.requireNonNull(deadLetter, "deadLetter must not be null");
        if (queue.offer(deadLetter)) {
            accepted.increment();
            return true;
        }
        if (overflowPolicy == OverflowPolicy.DROP_NEWEST) {
            dropped.increment();
            return false;
        }
        queue.poll();
        if (queue.offer(deadLetter)) {
            accepted.increment();
            dropped.increment();
            return true;
        }
        dropped.increment();
        return false;
    }

    /** Retrieves and removes the oldest available dead-letter, or null immediately. */
    public ObservationDeadLetter poll() {
        return queue.poll();
    }

    /** Retrieves the oldest available dead-letter, waiting up to the supplied timeout. */
    public ObservationDeadLetter poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, Objects.requireNonNull(unit, "unit must not be null"));
    }

    /**
     * Removes up to {@code maxItems} oldest letters without blocking.
     * The returned list is immutable and preserves queue order.
     */
    public List<ObservationDeadLetter> drain(int maxItems) {
        if (maxItems < 1) throw new IllegalArgumentException("maxItems must be greater than zero");
        var drained = new ArrayList<ObservationDeadLetter>(Math.min(maxItems, queue.size()));
        queue.drainTo(drained, maxItems);
        return List.copyOf(drained);
    }

    /** Current retained entries; safe to expose as a low-cardinality gauge value. */
    public int size() { return queue.size(); }

    /** Configured maximum number of retained entries. */
    public int capacity() { return queue.size() + queue.remainingCapacity(); }

    /** Remaining free slots. */
    public int remainingCapacity() { return queue.remainingCapacity(); }

    /** Current occupancy in [0.0, 1.0]. */
    public double utilization() { return (double) size() / capacity(); }

    /** Total entries successfully retained, including DROP_OLDEST replacements. */
    public long acceptedCount() { return accepted.sum(); }

    /** Total entries discarded by either overflow policy. */
    public long droppedCount() { return dropped.sum(); }
}