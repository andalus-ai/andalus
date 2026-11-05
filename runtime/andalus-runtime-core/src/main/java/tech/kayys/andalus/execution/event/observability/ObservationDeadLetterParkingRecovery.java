package tech.kayys.andalus.execution.event.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Explicit, bounded recovery from parked observation dead-letters to the active
 * queue. The queue is populated before the parking record is removed; this
 * avoids losing an entry when the queue is full, at the cost of a brief
 * duplicate window. Callers should make downstream handling idempotent.
 */
public final class ObservationDeadLetterParkingRecovery {
    private final ObservationDeadLetterParkingStore store;
    private final BoundedObservationDeadLetterQueue queue;

    public ObservationDeadLetterParkingRecovery(
            ObservationDeadLetterParkingStore store,
            BoundedObservationDeadLetterQueue queue) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.queue = Objects.requireNonNull(queue, "queue must not be null");
    }

    public synchronized RecoveryResult recover(String parkingId) {
        var parked = store.find(requireId(parkingId));
        if (parked.isEmpty()) return new RecoveryResult(Status.NOT_FOUND, null);
        var entry = parked.get();
        if (!queue.offerWithoutEviction(entry.deadLetter())) {
            return new RecoveryResult(Status.QUEUE_CAPACITY_REACHED, entry);
        }
        var removed = store.remove(entry.parkingId());
        if (removed.isEmpty()) {
            // Queue retention succeeded but another owner removed the park record.
            // Report possible duplicate instead of claiming an atomic transfer.
            return new RecoveryResult(Status.DUPLICATE_POSSIBLE, entry);
        }
        return new RecoveryResult(Status.REACTIVATED, removed.get());
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
