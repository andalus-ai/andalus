package tech.kayys.andalus.execution.event.observability;

import java.util.concurrent.atomic.LongAdder;

/**
 * Process-local operational counters for the parking and recovery adapters.
 * Counters are monotonic; current occupancy/capacity remain sourced from the
 * parking store so provider-specific storage is not duplicated here.
 */
public final class ObservationDeadLetterParkingMetrics {
    private final LongAdder stored = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder rejectedRequeued = new LongAdder();
    private final LongAdder rejectedUnretained = new LongAdder();
    private final LongAdder recoverySucceeded = new LongAdder();
    private final LongAdder recoveryNotFound = new LongAdder();
    private final LongAdder recoveryQueueFull = new LongAdder();
    private final LongAdder recoveryDuplicatePossible = new LongAdder();

    void recordParkStored() { stored.increment(); }
    void recordParkRejected() { rejected.increment(); }
    void recordParkRejectedRequeued() { rejectedRequeued.increment(); }
    void recordParkRejectedUnretained() { rejectedUnretained.increment(); }

    void recordRecovery(ObservationDeadLetterParkingRecovery.Status status) {
        switch (status) {
            case REACTIVATED -> recoverySucceeded.increment();
            case NOT_FOUND -> recoveryNotFound.increment();
            case QUEUE_CAPACITY_REACHED -> recoveryQueueFull.increment();
            case DUPLICATE_POSSIBLE -> recoveryDuplicatePossible.increment();
        }
    }

    public Snapshot snapshot(ObservationDeadLetterParkingStore.ParkingMetrics storeMetrics) {
        return new Snapshot(stored.sum(), rejected.sum(), rejectedRequeued.sum(),
                rejectedUnretained.sum(), recoverySucceeded.sum(),
                recoveryNotFound.sum(), recoveryQueueFull.sum(),
                recoveryDuplicatePossible.sum(), storeMetrics);
    }

    public record Snapshot(
            long stored,
            long rejected,
            long rejectedRequeued,
            long rejectedUnretained,
            long recoverySucceeded,
            long recoveryNotFound,
            long recoveryQueueFull,
            long recoveryDuplicatePossible,
            ObservationDeadLetterParkingStore.ParkingMetrics store) {
        public Snapshot {
            if (stored < 0 || rejected < 0 || rejectedRequeued < 0
                    || rejectedUnretained < 0 || recoverySucceeded < 0
                    || recoveryNotFound < 0 || recoveryQueueFull < 0
                    || recoveryDuplicatePossible < 0) {
                throw new IllegalArgumentException("parking counters must not be negative");
            }
            if (rejected != rejectedRequeued + rejectedUnretained) {
                throw new IllegalArgumentException("rejected parks must be accounted as requeued or unretained");
            }\n            if (store == null) throw new NullPointerException("store metrics must not be null");
        }
    }
}
