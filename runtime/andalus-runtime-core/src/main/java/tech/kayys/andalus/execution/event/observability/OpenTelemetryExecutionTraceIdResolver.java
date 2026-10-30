package tech.kayys.andalus.execution.event.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.andalus.execution.event.ExecutionEvent;

import java.util.Objects;

/**
 * Uses the active OpenTelemetry trace when one is present.
 *
 * <p>Outside an active span, an execution-scoped synthetic identifier is
 * returned. The {@code execution:} prefix makes it explicit that this value
 * is a local grouping key, not an OpenTelemetry trace ID.</p>
 */
@ApplicationScoped
public class OpenTelemetryExecutionTraceIdResolver implements ExecutionTraceIdResolver {

    @Override
    public String resolve(ExecutionEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        SpanContext context = Span.current().getSpanContext();
        if (context.isValid()) {
            return context.getTraceId();
        }

        if (event.executionId() == null || event.executionId().isBlank()) {
            throw new IllegalArgumentException("event.executionId must not be blank");
        }

        return "execution:" + event.executionId();
    }
}
