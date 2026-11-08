package tech.kayys.andalus.execution.event.observability;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Explicit recovery from parked dead-letters to the active queue.
 *
 * <p>When the parking store supports leases, recovery claims the entry before
 * queue insertion and completes the claim only after insertion succeeds.
 * Legacy stores retain the original queue-first/remove behavior.</p>
 */
public final class ObservationDeadLetterParkingRecovery {
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(30);

    private final ObservationDeadLetterParkingStore store;
    private final BoundedObservationDeadLetterQueue queue;
    private final ObservationDeadLetterParkingMetrics metrics;
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
        String id = requireId(parkingId);
        if (store instanceof LeasedObservationDeadLetterParkingStore leasedStore) {
            return recoverWithLease(id, leasedStore);
        }
        return recoverLegacy(id);
    }

    private RecoveryResult recoverWithLease(
            String parkingId,
            LeasedObservationDeadLetterParkingStore leasedStore) {
        var claimResult = leasedStore.claim(parkingId, ownerId, leaseDuration);
        if (claimResult.status() == LeasedObservationDeadLetterParkingStore.ClaimStatus.NOT_FOUND) {
            return record(new RecoveryResult(Status.NOT_FOUND, null));
        }
        if (claimResult.status() == LeasedObservationDeadLetterParkingStore.ClaimStatus.ALREADY_CLAIMED) {
            return record(new RecoveryResult(Status.RECOVERY_IN_PROGRESS, null));
        }

        var claim = claimResult.claim();
        var entry = claim.entry();

        // Renew immediately before handing work to the queue. This is a
        // bounded hand-off renewal, not a background lease heartbeat: long
        // running processing belongs to the consumer after queue acceptance.
        var renewedClaim = leasedStore.renew(claim, leaseDuration);
        if (renewedClaim.isEmpty()) {
            return record(new RecoveryResult(Status.LEASE_LOST, entry));
        }
        claim = renewedClaim.get();

        if (!queue.offerWithoutEviction(entry.deadLetter())) {
            if (!leasedStore.release(claim)) {
                return record(new RecoveryResult(Status.LEASE_LOST, entry));
            }
            return record(new RecoveryResult(Status.QUEUE_CAPACITY_REACHED, entry));
        }

        var completed = leasedStore.complete(claim);
        if (completed.isEmpty()) {
            // The queue has accepted the entry, but ownership expired or was
            // replaced before the store could atomically remove the parked copy.
            return record(new RecoveryResult(Status.DUPLICATE_POSSIBLE, entry));
        }
        return record(new RecoveryResult(Status.REACTIVATED, completed.get()));
    }

    private RecoveryResult recoverLegacy(String parkingId) {
        var parked = store.find(parkingId);
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
