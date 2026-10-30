package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.execution.event.EventLedger;
import tech.kayys.andalus.execution.event.ExecutionEvent;
import tech.kayys.andalus.execution.event.ExecutionEventType;
import tech.kayys.andalus.execution.event.ExecutionMetrics;
import tech.kayys.andalus.execution.event.InMemoryEventLedger;
import tech.kayys.andalus.observability.ObservationEvent;
import tech.kayys.andalus.observability.ObservabilityPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EventLedgerObservabilityDecoratorTest {

    @Test
    void mapsCanonicalEventWithoutCopyingUnclassifiedPayload() {
        ExecutionEvent event = new ExecutionEvent(
                "event-1",
                "exec-1",
                17,
                java.time.Instant.parse("2026-10-30T10:15:30Z"),
                ExecutionEventType.TOOL_EXECUTED,
                "tool-runner",
                Map.of("secret", "must-not-leak"),
                "parent-request-1"
        );

        ObservationEvent observation =
                new ExecutionEventObservationMapper(ignored -> "trace-123").map(event);

        assertEquals("event-1", observation.id());
        assertEquals("exec-1", observation.executionId());
        assertEquals("trace-123", observation.traceId());
        assertEquals("TOOL_EXECUTED", observation.type());
        assertEquals(event.timestamp(), observation.timestamp());
        assertEquals(17L, observation.attributes().get("sequence"));
        assertEquals("tool-runner", observation.attributes().get("actor"));
        assertEquals("parent-request-1", observation.attributes().get("correlationId"));
        assertFalse(observation.attributes().containsKey("secret"));
    }

    @Test
    void recordsToCanonicalLedgerBeforePublishingObservation() {
        InMemoryEventLedger ledger = new InMemoryEventLedger();
        List<ObservationEvent> published = new ArrayList<>();
        ObservabilityPublisher publisher = published::add;
        EventLedgerObservabilityDecorator decorator =
                new EventLedgerObservabilityDecorator(ledger, publisher, event -> "trace-1");

        ExecutionEvent event = ExecutionEvent.of(
                "exec-1", 1, ExecutionEventType.EXECUTION_STARTED, "runtime", Map.of());

        decorator.record(event);

        assertEquals(List.of(event), ledger.events("exec-1"));
        assertEquals(1, published.size());
        assertEquals(event.eventId(), published.getFirst().id());
    }

    @Test
    void publisherFailureDoesNotUndoSuccessfulLedgerAppend() {
        InMemoryEventLedger ledger = new InMemoryEventLedger();
        ObservabilityPublisher publisher = event -> {
            throw new IllegalStateException("sink unavailable");
        };
        EventLedgerObservabilityDecorator decorator =
                new EventLedgerObservabilityDecorator(ledger, publisher, event -> "trace-1");

        ExecutionEvent event = ExecutionEvent.of(
                "exec-1", 1, ExecutionEventType.EXECUTION_STARTED, "runtime", Map.of());

        assertDoesNotThrow(() -> decorator.record(event));
        assertEquals(1, ledger.totalEventCount());
    }

    @Test
    void failedCanonicalAppendIsNotPublished() {
        EventLedger failingLedger = new InMemoryEventLedger() {
            @Override
            public void record(ExecutionEvent event) {
                throw new IllegalStateException("ledger unavailable");
            }
        };
        List<ObservationEvent> published = new ArrayList<>();
        EventLedgerObservabilityDecorator decorator =
                new EventLedgerObservabilityDecorator(failingLedger, published::add, event -> "trace-1");

        assertThrows(IllegalStateException.class, () -> decorator.record(
                ExecutionEvent.of("exec-1", 1, ExecutionEventType.EXECUTION_STARTED, "runtime", Map.of())));
        assertTrue(published.isEmpty());
    }

    @Test
    void delegatesQueriesAndPurgeUnchanged() {
        InMemoryEventLedger ledger = new InMemoryEventLedger();
        EventLedgerObservabilityDecorator decorator =
                new EventLedgerObservabilityDecorator(ledger, event -> { }, event -> "trace-1");
        ExecutionEvent event = ExecutionEvent.of(
                "exec-1", 1, ExecutionEventType.TOOL_EXECUTED, "tool", Map.of());

        decorator.record(event);

        assertEquals(ledger.events("exec-1"), decorator.events("exec-1"));
        assertEquals(ledger.events("exec-1", ExecutionEventType.TOOL_EXECUTED),
                decorator.events("exec-1", ExecutionEventType.TOOL_EXECUTED));
        assertEquals(ledger.metrics("exec-1").map(ExecutionMetrics::executionId),
                decorator.metrics("exec-1").map(ExecutionMetrics::executionId));
        assertEquals(ledger.totalEventCount(), decorator.totalEventCount());

        decorator.purge("exec-1");
        assertTrue(decorator.events("exec-1").isEmpty());
        assertEquals(0, decorator.totalEventCount());
    }
}
