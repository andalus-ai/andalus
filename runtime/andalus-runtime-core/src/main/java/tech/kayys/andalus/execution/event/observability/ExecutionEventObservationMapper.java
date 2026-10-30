package tech.kayys.andalus.execution.event.observability;

import tech.kayys.andalus.execution.event.ExecutionEvent;
import tech.kayys.andalus.observability.ObservationEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Projects canonical EventLedger records into the normalized observation
 * stream. Payloads are intentionally excluded until a dedicated
 * classification/redaction policy is available.
 */
public final class ExecutionEventObservationMapper {

    private final ExecutionTraceIdResolver traceIdResolver;

    public ExecutionEventObservationMapper(ExecutionTraceIdResolver traceIdResolver) {
        this.traceIdResolver = Objects.requireNonNull(traceIdResolver, "traceIdResolver must not be null");
    }

    public ObservationEvent map(ExecutionEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (event.executionId() == null || event.executionId().isBlank()) {
            throw new IllegalArgumentException("event.executionId must not be blank");
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("sequence", event.sequence());
        if (event.actor() != null && !event.actor().isBlank()) {
            attributes.put("actor", event.actor());
        }
        if (event.correlationId() != null && !event.correlationId().isBlank()) {
            attributes.put("correlationId", event.correlationId());
        }

        String type = event.type() == null ? "UNKNOWN" : event.type().name();
        return new ObservationEvent(
                event.eventId(),
                event.executionId(),
                traceIdResolver.resolve(event),
                type,
                event.timestamp(),
                attributes
        );
    }
}
