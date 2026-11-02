package tech.kayys.andalus.coordination.discovery;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.coordination.CoordinationContext;

class InMemoryAgentRuntimeStateProviderTest {
    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void resolvesOnlyWithinTheLeaseTenant() {
        var provider = new InMemoryAgentRuntimeStateProvider(Clock.fixed(NOW, ZoneOffset.UTC));
        var state = new AgentRuntimeState(true, 4, 1.5);
        provider.publish(new AgentRuntimeStateLease("agent-a", "tenant-a", state, NOW.plusSeconds(30)));

        assertEquals(state, provider.resolve("agent-a", null, CoordinationContext.of("tenant-a", "user")).orElseThrow());
        assertTrue(provider.resolve("agent-a", null, CoordinationContext.of("tenant-b", "user")).isEmpty());
        assertTrue(provider.resolve("agent-a", null, CoordinationContext.create()).isEmpty());
    }

    @Test
    void expiredLeaseIsNotResolvedAndIsRemoved() {
        var provider = new InMemoryAgentRuntimeStateProvider(Clock.fixed(NOW, ZoneOffset.UTC));
        provider.publish(new AgentRuntimeStateLease("agent-a", "tenant-a",
                new AgentRuntimeState(true, 0, 1.0), NOW.plusSeconds(1)));

        var atExpiry = new InMemoryAgentRuntimeStateProvider(Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        // A lease can be accepted before expiry but must be invisible at the expiry instant.
        var beforeExpiry = new InMemoryAgentRuntimeStateProvider(
                Clock.fixed(NOW.minusSeconds(1), ZoneOffset.UTC));
        beforeExpiry.publish(new AgentRuntimeStateLease("agent-b", "tenant-a",
                new AgentRuntimeState(true, 0, 1.0), NOW.plusSeconds(1)));

        assertTrue(atExpiry.resolve("agent-a", null, CoordinationContext.of("tenant-a", "user")).isEmpty());
        assertTrue(beforeExpiry.resolve("agent-b", null, CoordinationContext.of("tenant-a", "user")).isPresent());
        assertTrue(provider.resolve("agent-a", null, CoordinationContext.of("tenant-a", "user")).isPresent());
    }

    @Test
    void rejectsInvalidAndAlreadyExpiredLeases() {
        var provider = new InMemoryAgentRuntimeStateProvider(Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(IllegalArgumentException.class, () -> provider.publish(null));
        assertThrows(IllegalArgumentException.class, () -> provider.publish(new AgentRuntimeStateLease(
                "agent-a", "tenant-a", new AgentRuntimeState(true, 0, 1.0), NOW)));
        assertThrows(IllegalArgumentException.class, () -> new AgentRuntimeStateLease(
                " ", "tenant-a", new AgentRuntimeState(true, 0, 1.0), NOW.plusSeconds(1)));
    }

    @Test
    void revokeIsTenantScoped() {
        var provider = new InMemoryAgentRuntimeStateProvider(Clock.fixed(NOW, ZoneOffset.UTC));
        provider.publish(new AgentRuntimeStateLease("agent-a", "tenant-a",
                new AgentRuntimeState(true, 0, 1.0), NOW.plusSeconds(10)));
        assertFalse(provider.revoke("tenant-b", "agent-a"));
        assertTrue(provider.revoke("tenant-a", "agent-a"));
        assertFalse(provider.revoke("tenant-a", "agent-a"));
    }
}
