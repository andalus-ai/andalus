package tech.kayys.andalus.execution.event.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Synchronous, bounded consumer for an in-memory observation dead-letter queue.
 *
 * <p>The legacy void-handler API remains available. Disposition-aware consumers
 * explicitly classify each entry as acknowledged, retried, or parked. RETRY
 * entries are requeued after the batch using a no-eviction offer, so they are
 * never processed again in the same invocation and never evict existing work.</p>
 *
 * <p>This class does not provide durable acknowledgment or crash recovery.
 * Entries drained from the in-memory queue can still be lost if the process
 * terminates before the result is handled or a retry is requeued.</p>
 */
public final class ObservationDeadLetterConsumer {
    private final BoundedObservationDeadLetterQueue queue;
    private final ObservationDeadLetterHandler handler;
    private final ObservationDeadLetterDispositionHandler dispositionHandler;

    public ObservationDeadLetterConsumer(
            BoundedObservationDeadLetterQueue queue,
            ObservationDeadLetterHandler handler) {
        this.queue = Objects.requireNonNull(queue, "queue must not be null");
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
        this.dispositionHandler = null;
    }

    private ObservationDeadLetterConsumer(
            BoundedObservationDeadLetterQueue queue,
            ObservationDeadLetterDispositionHandler handler) {
        this.queue = Objects.requireNonNull(queue, "queue must not be null");
        this.handler = null;
        this.dispositionHandler = Objects.requireNonNull(handler, "handler must not be null");
    }

    /** Creates a consumer whose handler returns an explicit ACK/RETRY/PARK disposition. */
    public static ObservationDeadLetterConsumer withDispositionHandler(
            BoundedObservationDeadLetterQueue queue,
            ObservationDeadLetterDispositionHandler handler) {
        return new ObservationDeadLetterConsumer(queue, handler);
    }

    /** Retains an entry only if active queue capacity is available; never evicts. */
    public boolean requeueWithoutEviction(ObservationDeadLetter deadLetter) {
        return queue.offerWithoutEviction(deadLetter);
    }

    /**
     * Legacy consumption contract: successful return counts as handled, while
     * exceptions are returned to the caller without automatic requeue.
     */
    public BatchResult consume(int maxItems) {
        requireLegacyHandler();
        List<ObservationDeadLetter> batch = queue.drain(maxItems);
        int handled = 0;
        List<FailedEntry> failures = new ArrayList<>();
        for (ObservationDeadLetter deadLetter : batch) {
            try {
                handler.handle(deadLetter);
                handled++;
            } catch (Exception failure) {
                failures.add(new FailedEntry(deadLetter, failure));
            }
        }
        return new BatchResult(batch.size(), handled, failures);
    }

    /**
     * Consumes one bounded snapshot and applies each returned disposition.
     * RETRY is requeued after all handlers complete; PARK is returned to the
     * caller and is not silently acknowledged or stored elsewhere.
     */
    public DispositionBatchResult consumeWithDisposition(int maxItems) {
        requireDispositionHandler();
        List<ObservationDeadLetter> batch = queue.drain(maxItems);
        List<ObservationDeadLetter> acknowledged = new ArrayList<>();
        List<RetryEntry> retried = new ArrayList<>();
        List<ObservationDeadLetter> retryRejected = new ArrayList<>();
        List<ParkedEntry> parked = new ArrayList<>();
        List<FailedEntry> failures = new ArrayList<>();

        for (ObservationDeadLetter deadLetter : batch) {
            try {
                ObservationDeadLetterDisposition disposition =
                        Objects.requireNonNull(dispositionHandler.handle(deadLetter),
                                "disposition handler returned null");
                switch (disposition.action()) {
                    case ACKNOWLEDGE -> acknowledged.add(deadLetter);
                    case RETRY -> retried.add(new RetryEntry(deadLetter, disposition.reason()));
                    case PARK -> parked.add(new ParkedEntry(deadLetter, disposition.reason()));
                }
            } catch (Exception failure) {
                failures.add(new FailedEntry(deadLetter, failure));
            }
        }

        for (RetryEntry retry : retried) {
            if (!queue.offerWithoutEviction(retry.deadLetter())) {
                retryRejected.add(retry.deadLetter());
            }
        }
        for (FailedEntry failure : failures) {
            if (!queue.offerWithoutEviction(failure.deadLetter())) {
                retryRejected.add(failure.deadLetter());
            }
        }

        return new DispositionBatchResult(batch.size(), acknowledged, retried,
                retryRejected, parked, failures);
    }

    private void requireLegacyHandler() {
        if (handler == null) {
            throw new IllegalStateException("consumer was created with a disposition handler");
        }
    }

    private void requireDispositionHandler() {
        if (dispositionHandler == null) {
            throw new IllegalStateException("consumer was created with a legacy handler");
        }
    }

    public record FailedEntry(ObservationDeadLetter deadLetter, Exception cause) {
        public FailedEntry {
            Objects.requireNonNull(deadLetter, "deadLetter must not be null");
            Objects.requireNonNull(cause, "cause must not be null");
        }
    }

    public record BatchResult(int polled, int handled, List<FailedEntry> failures) {
        public BatchResult {
            if (polled < 0 || handled < 0 || handled > polled) {
                throw new IllegalArgumentException("invalid batch counts");
            }
            failures = List.copyOf(Objects.requireNonNull(failures, "failures must not be null"));
            if (handled + failures.size() != polled) {
                throw new IllegalArgumentException("every polled entry must have one outcome");
            }
        }
        public int failed() { return failures.size(); }
        public boolean successful() { return failures.isEmpty(); }
    }

    public record RetryEntry(ObservationDeadLetter deadLetter, String reason) {
        public RetryEntry {
            Objects.requireNonNull(deadLetter, "deadLetter must not be null");
            reason = reason == null ? "" : reason;
        }
    }

    public record ParkedEntry(ObservationDeadLetter deadLetter, String reason) {
        public ParkedEntry {
            Objects.requireNonNull(deadLetter, "deadLetter must not be null");
            reason = reason == null ? "" : reason;
        }
    }

    public record DispositionBatchResult(
            int polled,
            List<ObservationDeadLetter> acknowledged,
            List<RetryEntry> retryRequested,
            List<ObservationDeadLetter> retryRejected,
            List<ParkedEntry> parked,
            List<FailedEntry> failures) {
        public DispositionBatchResult {
            if (polled < 0) throw new IllegalArgumentException("polled must not be negative");
            acknowledged = List.copyOf(Objects.requireNonNull(acknowledged, "acknowledged must not be null"));
            retryRequested = List.copyOf(Objects.requireNonNull(retryRequested, "retryRequested must not be null"));
            retryRejected = List.copyOf(Objects.requireNonNull(retryRejected, "retryRejected must not be null"));
            parked = List.copyOf(Objects.requireNonNull(parked, "parked must not be null"));
            failures = List.copyOf(Objects.requireNonNull(failures, "failures must not be null"));
            if (acknowledged.size() + retryRequested.size() + parked.size() + failures.size() != polled) {
                throw new IllegalArgumentException("every polled entry must have one disposition");
            }
        }
        public int acknowledgedCount() { return acknowledged.size(); }
        public int retryCount() { return retryRequested.size(); }
        public int retryRejectedCount() { return retryRejected.size(); }
        public int parkedCount() { return parked.size(); }
        public int failedCount() { return failures.size(); }
        public boolean successful() { return failures.isEmpty() && retryRejected.isEmpty(); }
    }
}
