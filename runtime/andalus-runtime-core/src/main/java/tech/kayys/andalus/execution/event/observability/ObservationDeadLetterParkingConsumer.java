package tech.kayys.andalus.execution.event.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies PARK dispositions to the parking SPI. If parking capacity rejects an
 * entry, the adapter attempts to retain it in the active queue without eviction.
 * Both outcomes are returned explicitly; no durability is implied.
 */
public final class ObservationDeadLetterParkingConsumer {
    private final ObservationDeadLetterConsumer consumer;
    private final ObservationDeadLetterParkingStore store;
    private final ObservationDeadLetterParkingMetrics metrics;

    public ObservationDeadLetterParkingConsumer(
            ObservationDeadLetterConsumer consumer,
            ObservationDeadLetterParkingStore store) {
        this(consumer, store, new ObservationDeadLetterParkingMetrics());
    }

    public ObservationDeadLetterParkingConsumer(
            ObservationDeadLetterConsumer consumer,
            ObservationDeadLetterParkingStore store,
            ObservationDeadLetterParkingMetrics metrics) {
        this.consumer = Objects.requireNonNull(consumer, "consumer must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    public ParkingBatchResult consume(int maxItems) {
        var batch = consumer.consumeWithDisposition(maxItems);
        var stored = new ArrayList<ParkedObservationDeadLetter>();
        var rejected = new ArrayList<RejectedEntry>();
        var requeued = new ArrayList<ObservationDeadLetterConsumer.ParkedEntry>();
        var unretained = new ArrayList<ObservationDeadLetterConsumer.ParkedEntry>();
        for (var entry : batch.parked()) {
            var result = store.park(entry.deadLetter(), entry.reason());
            if (result.stored()) {
                stored.add(result.entry());
                metrics.recordParkStored();
            } else {
                metrics.recordParkRejected();
                if (consumer.requeueWithoutEviction(entry.deadLetter())) {
                    requeued.add(entry);
                    metrics.recordParkRejectedRequeued();
                } else {
                    unretained.add(entry);
                    metrics.recordParkRejectedUnretained();
                }
                rejected.add(new RejectedEntry(entry, result.status(),
                        requeued.contains(entry)));
            }
        }
        return new ParkingBatchResult(batch, stored, rejected, requeued, unretained);
    }

    public ObservationDeadLetterParkingMetrics.Snapshot metrics() {
        return metrics.snapshot(store.metrics());
    }

    public record RejectedEntry(
            ObservationDeadLetterConsumer.ParkedEntry entry,
            ObservationDeadLetterParkingStore.ParkStatus status,
            boolean requeued) {
        public RejectedEntry {
            Objects.requireNonNull(entry, "entry must not be null");
            Objects.requireNonNull(status, "status must not be null");
        }
    }

    public record ParkingBatchResult(
            ObservationDeadLetterConsumer.DispositionBatchResult dispositions,
            List<ParkedObservationDeadLetter> stored,
            List<RejectedEntry> rejected,
            List<ObservationDeadLetterConsumer.ParkedEntry> requeued,
            List<ObservationDeadLetterConsumer.ParkedEntry> unretained) {
        public ParkingBatchResult {
            Objects.requireNonNull(dispositions, "dispositions must not be null");
            stored = List.copyOf(Objects.requireNonNull(stored, "stored must not be null"));
            rejected = List.copyOf(Objects.requireNonNull(rejected, "rejected must not be null"));
            requeued = List.copyOf(Objects.requireNonNull(requeued, "requeued must not be null"));
            unretained = List.copyOf(Objects.requireNonNull(unretained, "unretained must not be null"));
            if (requeued.size() + unretained.size() != rejected.size()) {
                throw new IllegalArgumentException("each rejected park must be requeued or reported unretained");
            }
            var requeuedSet = new java.util.HashSet<>(requeued);
            var unretainedSet = new java.util.HashSet<>(unretained);
            if (requeuedSet.size() != requeued.size() || unretainedSet.size() != unretained.size()) {
                throw new IllegalArgumentException("requeue and unretained outcomes must not contain duplicates");
            }
            for (var entry : rejected) {
                boolean wasRequeued = requeuedSet.contains(entry.entry());
                boolean wasUnretained = unretainedSet.contains(entry.entry());
                if (wasRequeued == wasUnretained || entry.requeued() != wasRequeued) {
                    throw new IllegalArgumentException("each rejected entry must match exactly one reported outcome");
                }
            }
            for (var entry : requeued) {
                if (rejected.stream().noneMatch(rejection -> rejection.entry().equals(entry))) {
                    throw new IllegalArgumentException("requeued entries must be present in rejected outcomes");
                }
            }
            for (var entry : unretained) {
                if (rejected.stream().noneMatch(rejection -> rejection.entry().equals(entry))) {
                    throw new IllegalArgumentException("unretained entries must be present in rejected outcomes");
                }
            }
        }
        public int storedCount() { return stored.size(); }
        public int rejectedCount() { return rejected.size(); }
        public int requeuedCount() { return requeued.size(); }
        public int unretainedCount() { return unretained.size(); }
        public boolean successful() {
            return dispositions.successful() && rejected.isEmpty();
        }
    }
}
