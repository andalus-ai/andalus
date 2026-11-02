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
    void lifecycleTransitionsRequireIncreasingGenerations() {
        var provider = new InMemoryAgentRuntimeStateProvider(Clock.fixed(NOW, ZoneOffset.UTC));
        var observer = observer(provider);

        observer.onLifecycleEvent(event(AgentRuntimeLifecycleEvent.Type.STARTED, "host-a", 1));
        assertFalse(resolve(provider).available());

        observer.onLifecycleEvent(event(AgentRuntimeLifecycleEvent.Type.HEALTHY, "host-a", 2));
        assertTrue(resolve(provider).available());

        observer.onLifecycleEvent(event(AgentRuntimeLifecycleEvent.Type.UNHEALTHY, "host-a", 3));
        assertFalse(resolve(provider).available());
    }

    @Test
    void staleLifecycleEventsAndStopFromPreviousOwnerCannotChangeCurrentLease() {
        var provider = new InMemoryAgentRuntimeStateProvider(Clock.fixed(NOW, ZoneOffset.UTC));
        var observer = observer(provider);

        observer.onLifecycleEvent(event(AgentRuntimeLifecycleEvent.Type.HEALTHY, "host-old", 4));
        observer.onLifecycleEvent(event(AgentRuntimeLifecycleEvent.Type.HEALTHY, "host-new", 5));

        // Delayed events from the previous owner must not overwrite or revoke generation 5.
        observer.onLifecycleEvent(event(AgentRuntimeLifecycleEvent.Type.UNHEALTHY, "host-old", 4));
        observer.onLifecycleEvent(event(AgentRuntimeLifecycleEvent.Type.STOPPED, "host-old", 4));

        assertTrue(resolve(provider).available());
        assertEquals(new AgentRuntimeState(true, 3, 1.2), resolve(provider));
    }

    @Test
    void stoppedRevokesOnlyMatchingOwnerAndGeneration() {
        var provider = new InMemoryAgentRuntimeStateProvider(Clock.fixed(NOW, ZoneOffset.UTC));
        var observer = observer(provider);

        observer.onLifecycleEvent(event(AgentRuntimeLifecycleEvent.Type.HEALTHY, "host-a", 7));
        observer.onLifecycleEvent(event(AgentRuntimeLifecycleEvent.Type.STOPPED, "host-a", 6));
        assertTrue(resolve(provider).available());

        observer.onLifecycleEvent(event(AgentRuntimeLifecycleEvent.Type.STOPPED, "host-a", 7));
        assertTrue(provider.resolve("agent-a", null,
                CoordinationContext.of("tenant-a", "user")).isEmpty());
    }

    @Test
    void rejectsInvalidFencingMetadataAndNonPositiveTtl() {
        assertThrows(IllegalArgumentException.class, () -> event(
                AgentRuntimeLifecycleEvent.Type.HEALTHY, "host-a", 0));
        assertThrows(IllegalArgumentException.class, () -> new AgentRuntimeLifecycleEvent(
                "agent-a", "tenant-a", " ", 1,
                AgentRuntimeLifecycleEvent.Type.HEALTHY, 3, 1.2));

        var provider = new InMemoryAgentRuntimeStateProvider(Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(IllegalArgumentException.class, () ->
                new AgentRuntimeLifecycleLeaseObserver(provider,
                        Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO));
    }

    private static AgentRuntimeLifecycleLeaseObserver observer(
            InMemoryAgentRuntimeStateProvider provider) {
        return new AgentRuntimeLifecycleLeaseObserver(provider,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(20));
    }

    private static AgentRuntimeState resolve(InMemoryAgentRuntimeStateProvider provider) {
        return provider.resolve("agent-a", null,
                CoordinationContext.of("tenant-a", "user")).orElseThrow();
    }

    private static AgentRuntimeLifecycleEvent event(
            AgentRuntimeLifecycleEvent.Type type, String owner, long generation) {
        return new AgentRuntimeLifecycleEvent(
                "agent-a", "tenant-a", owner, generation, type, 3, 1.2);
    }
}
