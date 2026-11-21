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
@ApplicationScoped
public class AgentRuntimeRegistrationReadiness {

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

    @Readiness
    public HealthCheck readinessCheck() {
        return () -> {
            State current = state.get();
            boolean up = current == State.READY || current == State.NOT_CONFIGURED;
            if (up) {
                return HealthCheckResponse.named("agent-runtime-registration")
                        .up()
                        .withData("state", current.name())
                        .build();
            }
            return HealthCheckResponse.named("agent-runtime-registration")
                    .down()
                    .withData("state", current.name())
                    .build();
        };
    }
}
