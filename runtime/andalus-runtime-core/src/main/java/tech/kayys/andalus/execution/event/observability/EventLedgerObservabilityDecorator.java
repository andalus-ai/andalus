package tech.kayys.andalus.execution.event.observability;

import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import tech.kayys.andalus.execution.event.EventLedger;
import tech.kayys.andalus.execution.event.ExecutionEvent;
import tech.kayys.andalus.execution.event.ExecutionEventType;
import tech.kayys.andalus.execution.event.ExecutionMetrics;
import tech.kayys.andalus.observability.ObservabilityPublisher;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * CDI decorator that mirrors successfully recorded ledger events to the
 * observability publisher without changing EventLedger query semantics.
 *
 * <p>The canonical ledger write always happens first. An observability
 * failure is isolated and cannot turn a successful ledger append into a
 * failed execution operation.</p>
 */
@Decorator
@Priority(Interceptor.Priority.APPLICATION)
public class EventLedgerObservabilityDecorator implements EventLedger {

    private final EventLedger delegate;
    private final ObservabilityPublisher publisher;
    private final ExecutionEventObservationMapper mapper;

    @Inject
    public EventLedgerObservabilityDecorator(
            @Delegate @Any EventLedger delegate,
            ObservabilityPublisher publisher,
            ExecutionTraceIdResolver traceIdResolver) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.mapper = new ExecutionEventObservationMapper(
                Objects.requireNonNull(traceIdResolver, "traceIdResolver must not be null"));
    }

    @Override
    public void record(ExecutionEvent event) {
        delegate.record(event);
        if (event == null || !publisher.enabled()) {
            return;
        }

        try {
            publisher.publish(mapper.map(event));
        } catch (RuntimeException ignored) {
            // Observability is a derived side channel; ledger success is authoritative.
        }
    }

    @Override
    public List<ExecutionEvent> events(String executionId) {
        return delegate.events(executionId);
    }

    @Override
    public List<ExecutionEvent> events(String executionId, ExecutionEventType type) {
        return delegate.events(executionId, type);
    }

    @Override
    public Optional<ExecutionMetrics> metrics(String executionId) {
        return delegate.metrics(executionId);
    }

    @Override
    public void purge(String executionId) {
        delegate.purge(executionId);
    }

    @Override
    public long totalEventCount() {
        return delegate.totalEventCount();
    }
}
