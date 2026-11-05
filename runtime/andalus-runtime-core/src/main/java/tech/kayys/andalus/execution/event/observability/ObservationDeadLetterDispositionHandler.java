package tech.kayys.andalus.execution.event.observability;

/**
 * Handler that returns an explicit disposition for one dead-letter entry.
 * Exceptions are reported by the consumer and requeued when capacity permits.
 */
@FunctionalInterface
public interface ObservationDeadLetterDispositionHandler {
    ObservationDeadLetterDisposition handle(ObservationDeadLetter deadLetter) throws Exception;
}
