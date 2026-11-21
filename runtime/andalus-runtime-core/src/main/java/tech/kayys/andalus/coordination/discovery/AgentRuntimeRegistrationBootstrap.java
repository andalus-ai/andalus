package tech.kayys.andalus.coordination.discovery;

import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import tech.kayys.andalus.agent.capability.AgentRuntimeLeaseToken;

/**
 * CDI/Quarkus lifecycle adapter for a host-provided registration identity and scheduler.
 *
 * <p>Registration is optional. Without an {@link AgentRuntimeRegistrationHost} bean
 * this adapter remains inert. When configured, exactly one host, scheduler and
 * registration lifecycle must be resolvable. The host still decides when local
 * readiness is complete by calling {@link #markAvailable()}.</p>
 */
@ApplicationScoped
public class AgentRuntimeRegistrationBootstrap {

    @Inject
    Instance<AgentRuntimeRegistrationHost> hosts;

    @Inject
    Instance<ScheduledExecutorService> schedulers;

    @Inject
    Instance<AgentRuntimeRegistrationLifecycle> lifecycles;

    @Inject
    Instance<AgentRuntimeRegistrationReadiness> readinessChecks;

    @Inject
    Instance<AgentRuntimeRegistrationTelemetry> telemetry;

    private volatile AgentRuntimeRegistrationSupervisor supervisor;

    void onStart(@Observes StartupEvent event) {
        if (hosts.isUnsatisfied()) {
            return;
        }
        if (hosts.isAmbiguous()) {
            throw new IllegalStateException("Exactly one AgentRuntimeRegistrationHost bean is required");
        }
        if (!schedulers.isResolvable() || !lifecycles.isResolvable()
                || !readinessChecks.isResolvable()) {
            throw new IllegalStateException(
                    "Agent registration is configured but requires exactly one "
                    + "ScheduledExecutorService, AgentRuntimeRegistrationLifecycle, "
                    + "and AgentRuntimeRegistrationReadiness bean");
        }

        AgentRuntimeRegistrationHost.Registration config = hosts.get().registration();
        AgentRuntimeRegistrationObserver observer = telemetry.isResolvable()
                ? telemetry.get() : AgentRuntimeRegistrationObserver.NOOP;
        AgentRuntimeRegistrationSupervisor candidate = new AgentRuntimeRegistrationSupervisor(
                lifecycles.get(), schedulers.get(), readinessChecks.get(),
                config.tenantId(), config.agentId(), config.instanceId(), config.ownerId(),
                config.heartbeatInterval(), config.initialRetryDelay(), config.maxRetryDelay(),
                observer);
        supervisor = candidate;
        candidate.start();
    }

    /** Host callback after local startup and dependency readiness have succeeded. */
    public boolean markAvailable() {
        AgentRuntimeRegistrationSupervisor current = supervisor;
        return current != null && current.markAvailable();
    }

    /** Host callback when local readiness is withdrawn but the process remains alive. */
    public boolean markUnavailable() {
        AgentRuntimeRegistrationSupervisor current = supervisor;
        return current != null && current.markUnavailable();
    }

    public boolean isReady() {
        AgentRuntimeRegistrationSupervisor current = supervisor;
        return current != null && current.isReady();
    }

    public Optional<AgentRuntimeLeaseToken> token() {
        AgentRuntimeRegistrationSupervisor current = supervisor;
        return current == null ? Optional.empty() : current.token();
    }

    void onStop(@Observes ShutdownEvent event) {
        AgentRuntimeRegistrationSupervisor current = supervisor;
        supervisor = null;
        if (current != null) {
            current.close();
        }
    }
}
