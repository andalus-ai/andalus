package tech.kayys.andalus.coordination.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tech.kayys.andalus.agent.capability.AgentRuntimeLease;
import tech.kayys.andalus.agent.capability.AgentRuntimeLeaseToken;
import tech.kayys.andalus.agent.capability.AgentRuntimeRegistrationRequest;
import tech.kayys.andalus.agent.capability.AgentRuntimeRegistrationStore;
import tech.kayys.andalus.agent.capability.AgentRuntimeStatus;

class AgentRuntimeRegistrationLifecycleTest {

    private static final Duration TTL = Duration.ofSeconds(20);
    private static final AgentRuntimeLeaseToken TOKEN =
            new AgentRuntimeLeaseToken("tenant-a", "agent-a", "instance-a", "owner-a", 4);

    @Test
    void startupReadinessHeartbeatAndShutdownUseRegistrationToken() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        AgentRuntimeRegistrationLifecycle lifecycle = new AgentRuntimeRegistrationLifecycle(store, TTL);
        AgentRuntimeLease starting = lease(AgentRuntimeStatus.STARTING);
        AgentRuntimeLease available = lease(AgentRuntimeStatus.AVAILABLE);
        when(store.acquire(any(AgentRuntimeRegistrationRequest.class))).thenReturn(Optional.of(starting));
        when(store.publishStatus(eq(TOKEN), eq(AgentRuntimeStatus.AVAILABLE), eq(TTL)))
                .thenReturn(Optional.of(available));
        when(store.renew(TOKEN, TTL)).thenReturn(Optional.of(available));
        when(store.revoke(TOKEN)).thenReturn(true);

        assertEquals(starting, lifecycle.start("tenant-a", "agent-a", "instance-a", "owner-a").orElseThrow());
        assertEquals(available, lifecycle.markAvailable(TOKEN).orElseThrow());
        assertEquals(available, lifecycle.heartbeat(TOKEN).orElseThrow());
        assertTrue(lifecycle.shutdown(TOKEN));

        verify(store).acquire(new AgentRuntimeRegistrationRequest(
                "tenant-a", "agent-a", "instance-a", "owner-a", TTL));
        verify(store).publishStatus(TOKEN, AgentRuntimeStatus.AVAILABLE, TTL);
        verify(store).renew(TOKEN, TTL);
        verify(store).revoke(TOKEN);
    }

    @Test
    void failedAcquisitionAndFencedOperationsRemainFailures() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        AgentRuntimeRegistrationLifecycle lifecycle = new AgentRuntimeRegistrationLifecycle(store, TTL);
        when(store.acquire(any(AgentRuntimeRegistrationRequest.class))).thenReturn(Optional.empty());
        when(store.publishStatus(TOKEN, AgentRuntimeStatus.UNAVAILABLE, TTL)).thenReturn(Optional.empty());
        when(store.renew(TOKEN, TTL)).thenReturn(Optional.empty());
        when(store.revoke(TOKEN)).thenReturn(false);

        assertTrue(lifecycle.start("tenant-a", "agent-a", "instance-a", "owner-a").isEmpty());
        assertTrue(lifecycle.markUnavailable(TOKEN).isEmpty());
        assertTrue(lifecycle.heartbeat(TOKEN).isEmpty());
        assertFalse(lifecycle.shutdown(TOKEN));
    }

    private static AgentRuntimeLease lease(AgentRuntimeStatus status) {
        return new AgentRuntimeLease(TOKEN, status, Instant.parse("2030-01-01T00:00:00Z"));
    }
}
