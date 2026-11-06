package tech.kayys.andalus.execution.event.observability;

import java.util.HashMap;\nimport java.util.Map;\nimport java.util.Objects;

/**
 * Explicit recovery from parked dead-letters to the active queue. Queue-first
 * transfer avoids loss on queue-capacity failure but can briefly duplicate.
 */
public final class ObservationDeadLetterParkingRecovery {
    private final ObservationDeadLetterParkingStore store;
    private final BoundedObservationDeadLetterQueue queue;
    private final ObservationDeadLetterParkingMetrics metrics;\n    // Prevent a second enqueue after this instance has observed an ambiguous removal.\n    private final Map<String, ParkedObservationDeadLetter> ambiguousRecoveries = new HashMap<>();

    public ObservationDeadLetterParkingRecovery(
            ObservationDeadLetterParkingStore store,
            BoundedObservationDeadLetterQueue queue) {
        this(store, queue, new ObservationDeadLetterParkingMetrics());
    }

    public ObservationDeadLetterParkingRecovery(
            ObservationDeadLetterParkingStore store,
            BoundedObservationDeadLetterQueue queue,
            ObservationDeadLetterParkingMetrics metrics) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.queue = Objects.requireNonNull(queue, "queue must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    public synchronized RecoveryResult recover(String parkingId) {
        var parked = store.find(requireId(parkingId));
        if (parked.isEmpty()) return record(new RecoveryResult(Status.NOT_FOUND, null));
        var entry = parked.get();
        if (!queue.offerWithoutEviction(entry.deadLetter())) {
            return record(new RecoveryResult(Status.QUEUE_CAPACITY_REACHED, entry));
        }
        var removed = store.remove(entry.parkingId());
        if (removed.isEmpty()) {
            return record(new RecoveryResult(Status.DUPLICATE_POSSIBLE, entry));
        }
        return record(new RecoveryResult(Status.REACTIVATED, removed.get()));
    }

    public ObservationDeadLetterParkingMetrics.Snapshot metrics() {
        return metrics.snapshot(store.metrics());
    }

    private RecoveryResult record(RecoveryResult result) {
        metrics.recordRecovery(result.status());
        return result;
    }

    private static String requireId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("parkingId must not be blank");
        return id;
    }

    public enum Status { REACTIVATED, NOT_FOUND, QUEUE_CAPACITY_REACHED, DUPLICATE_POSSIBLE }

    public record RecoveryResult(Status status, ParkedObservationDeadLetter entry) {
        public RecoveryResult {
            Objects.requireNonNull(status, "status must not be null");
            if (status == Status.REACTIVATED && entry == null)
                throw new IllegalArgumentException("reactivated result requires entry");
        }
        public boolean reactivated() { return status == Status.REACTIVATED; }
    }
}