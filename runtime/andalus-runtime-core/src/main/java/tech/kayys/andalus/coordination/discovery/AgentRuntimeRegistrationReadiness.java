package tech.kayys.andalus.coordination.discovery;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Opt-in readiness signal for hosts that require distributed agent registration.
 *
 * <p>It deliberately does not represent process liveness. An application that
 * does not use distributed registration leaves this check NOT_CONFIGURED and
 * therefore does not become unready.</p>
 */
@Readiness
@ApplicationScoped
public class AgentRuntimeRegistrationReadiness implements HealthCheck {

    public enum State {
        NOT_CONFIGURED,
        STARTING,
        READY,
        DEGRADED,
        STOPPED
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.NOT_CONFIGURED);

    public State state() {
        return state.get();
    }

    public boolean isReady() {
        State current = state.get();
        return current == State.READY || current == State.NOT_CONFIGURED;
    }

    public void starting() {
        state.set(State.STARTING);
    }

    public void ready() {
        state.set(State.READY);
    }

    public void degraded() {
        state.set(State.DEGRADED);
    }

    public void stopped() {
        state.set(State.STOPPED);
    }

    @Override
    public HealthCheckResponse call() {
        State current = state.get();
        boolean up = current == State.READY || current == State.NOT_CONFIGURED;
        return HealthCheckResponse.named("agent-runtime-registration")
                .status(up)
                .withData("state", current.name())
                .build();
    }
}
