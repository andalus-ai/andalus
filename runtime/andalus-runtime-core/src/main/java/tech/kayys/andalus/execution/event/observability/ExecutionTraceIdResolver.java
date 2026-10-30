package tech.kayys.andalus.execution.event.observability;

import tech.kayys.andalus.execution.event.ExecutionEvent;

/**
 * Resolves the trace identifier used when projecting a canonical execution
 * event into the observability event stream.
 *
 * <p>The resolver is deliberately separate from {@code correlationId}:
 * correlation identifiers may refer to parent events or external requests
 * and are not necessarily OpenTelemetry trace identifiers.</p>
 */
@FunctionalInterface
public interface ExecutionTraceIdResolver {

    String resolve(ExecutionEvent event);
}
