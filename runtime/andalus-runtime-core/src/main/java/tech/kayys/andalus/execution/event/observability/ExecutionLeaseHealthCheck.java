package tech.kayys.andalus.execution.event.observability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import java.time.Duration;

/**
 * Readiness signal for workers that have opted into lease heartbeats.
 * Bounded executions that do not emit renewals are not treated as unhealthy.
 */
@ApplicationScoped
public class ExecutionLeaseHealthCheck {
    private static final Duration HEARTBEAT_STALE_AFTER = Duration.ofSeconds(60);

    @Inject
    ExecutionLeaseMetricsObserver observer;

    @Readiness
    public HealthCheck readinessCheck() {
        return () -> {
            long active = observer.activeLeaseCount();
            long sinceHeartbeat = observer.millisSinceLastHeartbeat();
            boolean fresh = observer.heartbeatFresh(HEARTBEAT_STALE_AFTER);
            var response = HealthCheckResponse.named("execution-lease-heartbeats")
                    .withData("active.leases", active)
                    .withData("heartbeat.observed", sinceHeartbeat >= 0)
                    .withData("heartbeat.age.ms", sinceHeartbeat)
                    .withData("heartbeat.stale.after.ms", HEARTBEAT_STALE_AFTER.toMillis())
                    .withData("ownership.losses", observer.ownershipLossCount())
                    .withData("renewals.success", observer.renewalSuccessCount())
                    .withData("renewals.failure", observer.renewalFailureCount())
                    .withData("renewals.rejected", observer.renewalRejectionCount());
            return fresh ? response.up().build() : response.down().build();
        };
    }
}
