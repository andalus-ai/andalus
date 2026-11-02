package tech.kayys.andalus.execution.event.observability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import tech.kayys.andalus.observability.ObservabilityPublisher;

/**
 * Reports the health of the runtime-owned asynchronous observation worker.
 * Queue saturation and dropped events are diagnostic signals, not readiness
 * failures: taking the whole runtime out of service would amplify backpressure.
 */
@ApplicationScoped
public class ObservabilityPublisherHealthCheck {

    @Inject
    Instance<ObservabilityPublisher> publishers;

    @Readiness
    public HealthCheck readinessCheck() {
        return () -> {
            if (!publishers.isResolvable()) {
                return HealthCheckResponse.named("observability-publisher")
                        .up()
                        .withData("publisher", "not-configured")
                        .build();
            }

            ObservabilityPublisher publisher = publishers.get();
            if (!(publisher instanceof BoundedAsyncObservabilityPublisher async)) {
                return HealthCheckResponse.named("observability-publisher")
                        .up()
                        .withData("publisher", publisher.getClass().getSimpleName())
                        .build();
            }

            boolean workerAlive = async.isWorkerAlive();
            double utilization = async.queueCapacity() == 0
                    ? 0.0
                    : (double) async.queueSize() / async.queueCapacity();

            var response = HealthCheckResponse.named("observability-publisher")
                    .withData("worker.alive", workerAlive)
                    .withData("queue.size", async.queueSize())
                    .withData("queue.capacity", async.queueCapacity())
                    .withData("queue.utilization", utilization)
                    .withData("events.accepted", async.acceptedCount())
                    .withData("events.delivered", async.deliveredCount())
                    .withData("events.dropped", async.droppedCount())
                    .withData("delivery.failures", async.failureCount());

            return workerAlive ? response.up().build() : response.down().build();
        };
    }
}
