package tech.kayys.andalus.execution.event.observability;

/**
 * Consumer-side extension point for inspecting or handling a dead-letter entry.
 *
 * <p>Implementations are invoked by a separately managed consumer, never by the
 * observation publisher worker. The caller owns scheduling, bounded concurrency,
 * retry limits, acknowledgement/removal timing, and shutdown behavior.</p>
 *
 * <p>Handlers that re-publish or otherwise cause side effects should be
 * idempotent: this contract does not provide exactly-once delivery, durable
 * storage, deduplication, or automatic replay. A handler must not assume that
 * returning successfully means the original observation was delivered.</p>
 */
@FunctionalInterface
public interface ObservationDeadLetterHandler {
    /**
     * Inspects or handles one terminally failed observation.
     *
     * @param deadLetter terminal failure envelope
     * @throws Exception when the handler did not complete its operation;
     *                  retry policy belongs to the consuming component
     */
    void handle(ObservationDeadLetter deadLetter) throws Exception;
}
