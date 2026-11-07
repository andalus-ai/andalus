package tech.kayys.andalus.execution.event.observability;

import java.util.HashMap;
import java.util.Map;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

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
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(30);

    private final ObservationDeadLetterParkingStore store;
    private final BoundedObservationDeadLetterQueue queue;
    private final ObservationDeadLetterParkingMetrics metrics;
    private final Map<String, ParkedObservationDeadLetter> ambiguousRecoveries = new HashMap<>();
    private final String ownerId;
    private final Duration leaseDuration;

    public ObservationDeadLetterParkingRecovery(
            ObservationDeadLetterParkingStore store,
            BoundedObservationDeadLetterQueue queue) {
        this(store, queue, new ObservationDeadLetterParkingMetrics());
    }

    public ObservationDeadLetterParkingRecovery(
            ObservationDeadLetterParkingStore store,
            BoundedObservationDeadLetterQueue queue,
            ObservationDeadLetterParkingMetrics metrics) {
        this(store, queue, metrics, UUID.randomUUID().toString(), DEFAULT_LEASE_DURATION);
    }

    public ObservationDeadLetterParkingRecovery(
            ObservationDeadLetterParkingStore store,
            BoundedObservationDeadLetterQueue queue,
            ObservationDeadLetterParkingMetrics metrics,
            String ownerId,
            Duration leaseDuration) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.queue = Objects.requireNonNull(queue, "queue must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId must not be blank");
        }
        this.ownerId = ownerId;
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
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

    public enum Status {
        REACTIVATED,
        NOT_FOUND,
        QUEUE_CAPACITY_REACHED,
        DUPLICATE_POSSIBLE,
        RECOVERY_IN_PROGRESS,
        LEASE_LOST
    }

    public record RecoveryResult(Status status, ParkedObservationDeadLetter entry) {
        public RecoveryResult {
            Objects.requireNonNull(status, "status must not be null");
            if ((status == Status.REACTIVATED || status == Status.QUEUE_CAPACITY_REACHED
                    || status == Status.DUPLICATE_POSSIBLE || status == Status.LEASE_LOST)
                    && entry == null) {
                throw new IllegalArgumentException(status + " result requires entry");
            }
            if ((status == Status.NOT_FOUND || status == Status.RECOVERY_IN_PROGRESS) && entry != null) {
                throw new IllegalArgumentException(status + " result must not expose an entry");
            }
        }

        public boolean reactivated() { return status == Status.REACTIVATED; }
    }
}
