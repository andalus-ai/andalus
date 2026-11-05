package tech.kayys.andalus.execution.event.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Synchronous, bounded consumer for an in-memory observation dead-letter queue.
 *
 * <p>Each invocation removes at most the requested number of entries and invokes
 * the handler sequentially on the calling thread. Handler exceptions are
 * returned with their corresponding dead-letter instead of being discarded.
 * The caller is responsible for retaining or re-enqueuing failed entries.</p>
 *
 * <p>This class does not provide durable acknowledgment, retries, backoff,
 * scheduling, or concurrent-consumer coordination. Use one consumer per queue
 * unless the application supplies its own coordination and recovery strategy.</p>
 */
public final class ObservationDeadLetterConsumer {
    private final BoundedObservationDeadLetterQueue queue;
    private final ObservationDeadLetterHandler handler;

    public ObservationDeadLetterConsumer(
            BoundedObservationDeadLetterQueue queue,
            ObservationDeadLetterHandler handler) {
        this.queue = Objects.requireNonNull(queue, "queue must not be null");
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
    }

    /**
     * Handles up to {@code maxItems} oldest entries sequentially.
     *
     * <p>Entries are removed from the in-memory queue before handler invocation.
     * Any handler exception is included in the returned failure list so callers
     * can persist, retry, or re-enqueue it. A process crash during handling can
     * still lose an entry; this is not a durable acknowledgment protocol.</p>
     */
    public BatchResult consume(int maxItems) {
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

        public int failed() {
            return failures.size();
        }

        public boolean successful() {
            return failures.isEmpty();
        }
    }
}
