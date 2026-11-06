package tech.kayys.andalus.execution.event.observability;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit recovery from parked dead-letters to the active queue. Queue-first
 * transfer avoids loss on queue-capacity failure but can briefly duplicate.
 *
 * <p>When removal cannot be confirmed, this instance remembers the ambiguous
 * result and suppresses repeat enqueue attempts for that parking ID. This is
 * process-local protection only; durable or cross-instance idempotency requires
 * coordination in the parking-store implementation.</p>
 */
public final class ObservationDeadLetterParkingRecovery {
    private final ObservationDeadLetterParkingStore store;
    private final BoundedObservationDeadLetterQueue queue;
    private final ObservationDeadLetterParkingMetrics metrics;
    private final Map<String, ParkedObservationDeadLetter> ambiguousRecoveries = new HashMap<>();

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
        var id = requireId(parkingId);
        var ambiguous = ambiguousRecoveries.get(id);
        if (ambiguous != null) {
            return record(new RecoveryResult(Status.DUPLICATE_POSSIBLE, ambiguous));
        }
        var parked = store.find(id);
        if (parked.isEmpty()) return record(new RecoveryResult(Status.NOT_FOUND, null));
        var entry = parked.get();
        if (!queue.offerWithoutEviction(entry.deadLetter())) {
            return record(new RecoveryResult(Status.QUEUE_CAPACITY_REACHED, entry));
        }
        var removed = store.remove(entry.parkingId());
        if (removed.isEmpty()) {
            ambiguousRecoveries.put(entry.parkingId(), entry);
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
