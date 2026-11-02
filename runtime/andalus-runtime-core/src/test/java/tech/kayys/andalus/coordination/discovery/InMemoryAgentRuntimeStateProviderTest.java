package tech.kayys.andalus.coordination.discovery;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
        var clock = new MutableClock(NOW);
        var provider = new InMemoryAgentRuntimeStateProvider(clock);
        provider.publish(new AgentRuntimeStateLease("agent-a", "tenant-a",
                new AgentRuntimeState(true, 0, 1.0), NOW.plusSeconds(1)));

        clock.set(NOW.plusSeconds(1));
        assertTrue(provider.resolve("agent-a", null,
                CoordinationContext.of("tenant-a", "user")).isEmpty());
        assertFalse(provider.revoke("tenant-a", "agent-a"));
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

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void set(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
