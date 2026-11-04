package tech.kayys.andalus.coordination.discovery;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AgentRuntimeStateProviderFencedStopTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void newerStopGenerationRevokesPriorStateForSameOwner() {
        var provider = new InMemoryAgentRuntimeStateProvider(Clock.fixed(NOW, ZoneOffset.UTC));
        provider.publish(new AgentRuntimeStateLease(
                "agent-a", "tenant-a", new AgentRuntimeState(true, 1, 1.0),
                NOW.plusSeconds(30), "host-a", 4));

        assertTrue(provider.revokeAtOrBeforeGeneration("tenant-a", "agent-a", "host-a", 5));
        assertTrue(provider.resolve("agent-a", null,
                tech.kayys.andalus.coordination.CoordinationContext.of("tenant-a", "user")).isEmpty());
    }

    @Test
    void delayedStopCannotRevokeNewerStateOrDifferentOwner() {
        var provider = new InMemoryAgentRuntimeStateProvider(Clock.fixed(NOW, ZoneOffset.UTC));
        provider.publish(new AgentRuntimeStateLease(
                "agent-a", "tenant-a", new AgentRuntimeState(true, 1, 1.0),
                NOW.plusSeconds(30), "host-a", 4));
        provider.publish(new AgentRuntimeStateLease(
                "agent-a", "tenant-a", new AgentRuntimeState(true, 2, 1.0),
                NOW.plusSeconds(30), "host-b", 6));

        assertFalse(provider.revokeAtOrBeforeGeneration("tenant-a", "agent-a", "host-a", 5));
        assertFalse(provider.revokeAtOrBeforeGeneration("tenant-a", "agent-a", "host-b", 5));
        assertEquals(new AgentRuntimeState(true, 2, 1.0), provider.resolve(
                "agent-a", null,
                tech.kayys.andalus.coordination.CoordinationContext.of("tenant-a", "user")).orElseThrow());
    }
}
