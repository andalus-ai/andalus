package tech.kayys.andalus.execution.event.observability;

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

    public int size() { return queue.size(); }
    public int capacity() { return queue.size() + queue.remainingCapacity(); }
    public long acceptedCount() { return accepted.sum(); }
    public long droppedCount() { return dropped.sum(); }
}