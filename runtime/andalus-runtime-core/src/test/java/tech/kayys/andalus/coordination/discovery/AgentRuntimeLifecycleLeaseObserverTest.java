package tech.kayys.andalus.coordination.discovery;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.coordination.CoordinationContext;

class AgentRuntimeLifecycleLeaseObserverTest {
    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void startupIsNotReadyHealthyEnablesAndUnhealthyDisables() {
        var provider = new InMemoryAgentRuntimeStateProvider(Clock.fixed(NOW, ZoneOffset.UTC));
        var observer = new AgentRuntimeLifecycleLeaseObserver(provider,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(20));

        observer.onLifecycleEvent(event(AgentRuntimeLifecycleEvent.Type.STARTED));
        assertFalse(resolve(provider).available());
        observer.onLifecycleEvent(event(AgentRuntimeLifecycleEvent.Type.HEALTHY));
        assertTrue(resolve(provider).available());
        observer.onLifecycleEvent(event(AgentRuntimeLifecycleEvent.Type.UNHEALTHY));
        assertFalse(resolve(provider).available());
    }

    @Test
    void stoppedRevokesLease() {
        var provider = new InMemoryAgentRuntimeStateProvider(Clock.fixed(NOW, ZoneOffset.UTC));
        var observer = new AgentRuntimeLifecycleLeaseObserver(provider,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(20));
        observer.onLifecycleEvent(event(AgentRuntimeLifecycleEvent.Type.HEALTHY));
        observer.onLifecycleEvent(event(AgentRuntimeLifecycleEvent.Type.STOPPED));
        assertTrue(provider.resolve("agent-a", null,
                CoordinationContext.of("tenant-a", "user")).isEmpty());
    }

    @Test
    void rejectsNonPositiveLeaseTtl() {
        var provider = new InMemoryAgentRuntimeStateProvider(Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(IllegalArgumentException.class, () ->
                new AgentRuntimeLifecycleLeaseObserver(provider,
                        Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO));
    }

    private static AgentRuntimeState resolve(InMemoryAgentRuntimeStateProvider provider) {
        return provider.resolve("agent-a", null,
                CoordinationContext.of("tenant-a", "user")).orElseThrow();
    }

    private static AgentRuntimeLifecycleEvent event(AgentRuntimeLifecycleEvent.Type type) {
        return new AgentRuntimeLifecycleEvent("agent-a", "tenant-a", type, 3, 1.2);
    }
}
