package tech.kayys.andalus.execution.event.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Applies PARK dispositions to the parking SPI and reports capacity failures. */
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
        for (var entry : batch.parked()) {
            var result = store.park(entry.deadLetter(), entry.reason());
            if (result.stored()) {
                stored.add(result.entry());
                metrics.recordParkStored();
            } else {
                rejected.add(new RejectedEntry(entry, result.status()));
                metrics.recordParkRejected();
            }
        }
        return new ParkingBatchResult(batch, stored, rejected);
    }

    public ObservationDeadLetterParkingMetrics.Snapshot metrics() {
        return metrics.snapshot(store.metrics());
    }

    public record RejectedEntry(
            ObservationDeadLetterConsumer.ParkedEntry entry,
            ObservationDeadLetterParkingStore.ParkStatus status) {
        public RejectedEntry {
            Objects.requireNonNull(entry, "entry must not be null");
            Objects.requireNonNull(status, "status must not be null");
        }
    }

    public record ParkingBatchResult(
            ObservationDeadLetterConsumer.DispositionBatchResult dispositions,
            List<ParkedObservationDeadLetter> stored,
            List<RejectedEntry> rejected) {
        public ParkingBatchResult {
            Objects.requireNonNull(dispositions, "dispositions must not be null");
            stored = List.copyOf(Objects.requireNonNull(stored, "stored must not be null"));
            rejected = List.copyOf(Objects.requireNonNull(rejected, "rejected must not be null"));
        }
        public int storedCount() { return stored.size(); }
        public int rejectedCount() { return rejected.size(); }
        public boolean successful() { return dispositions.successful() && rejected.isEmpty(); }
    }
}