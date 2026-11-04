package tech.kayys.andalus.execution.event.observability;

/**
 * Optional bounded/externally managed dead-letter extension point.
 * Implementations must define their own capacity and persistence guarantees.
 */
@FunctionalInterface
public interface ObservationDeadLetterSink {
    void accept(ObservationDeadLetter deadLetter);
}
