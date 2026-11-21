package tech.kayys.andalus.coordination.discovery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import tech.kayys.andalus.agent.capability.AgentRuntimeLease;
import tech.kayys.andalus.agent.capability.AgentRuntimeLeaseToken;
import tech.kayys.andalus.agent.capability.AgentRuntimeRegistrationRequest;
import tech.kayys.andalus.agent.capability.AgentRuntimeRegistrationStore;
import tech.kayys.andalus.agent.capability.AgentRuntimeStatus;

class AgentRuntimeRegistrationGroupTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    @Test
    @SuppressWarnings("unchecked")
    void supervisesMultipleAgentsIndependentlyAndAggregatesReadiness() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(heartbeat);
        when(store.acquire(any())).thenAnswer(invocation -> {
            AgentRuntimeRegistrationRequest request = invocation.getArgument(0);
            return Optional.of(lease(token(request), AgentRuntimeStatus.STARTING));
        });
        when(store.publishStatus(any(), any(), any())).thenAnswer(invocation -> {
            AgentRuntimeLeaseToken token = invocation.getArgument(0);
            AgentRuntimeStatus status = invocation.getArgument(1);
            return Optional.of(lease(token, status));
        });
        when(store.revoke(any())).thenReturn(true);

        AgentRuntimeRegistrationLifecycle lifecycle = new AgentRuntimeRegistrationLifecycle(store, TTL);
        AgentRuntimeRegistrationGroup group = new AgentRuntimeRegistrationGroup(lifecycle, scheduler);

        AgentRuntimeRegistrationGroup.Key research = group.register(registration("research"));
        AgentRuntimeRegistrationGroup.Key planner = group.register(registration("planner"));

        assertEquals(2, group.size());
        assertFalse(group.isReady());
        assertTrue(group.markAvailable(research));
        assertFalse(group.isReady());
        assertTrue(group.markAvailable(planner));
        assertTrue(group.isReady());
        assertEquals(AgentRuntimeRegistrationReadiness.State.READY, group.states().get(research));
        assertEquals(AgentRuntimeRegistrationReadiness.State.READY, group.states().get(planner));

        assertTrue(group.markUnavailable(research));
        assertFalse(group.isReady());
        assertEquals(AgentRuntimeRegistrationReadiness.State.DEGRADED, group.states().get(research));
        assertEquals(AgentRuntimeRegistrationReadiness.State.READY, group.states().get(planner));

        group.close();
        assertEquals(0, group.size());
        assertFalse(group.isReady());
        verify(store, times(2)).acquire(any());
        verify(store, times(2)).revoke(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unregistersOneAgentWithoutClosingTheOther() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(mock(ScheduledFuture.class));
        when(store.acquire(any())).thenAnswer(invocation -> {
            AgentRuntimeRegistrationRequest request = invocation.getArgument(0);
            return Optional.of(lease(token(request), AgentRuntimeStatus.STARTING));
        });
        when(store.publishStatus(any(), any(), any())).thenAnswer(invocation ->
                Optional.of(lease(invocation.getArgument(0), invocation.getArgument(1))));
        when(store.revoke(any())).thenReturn(true);

        AgentRuntimeRegistrationGroup group = new AgentRuntimeRegistrationGroup(
                new AgentRuntimeRegistrationLifecycle(store, TTL), scheduler);
        AgentRuntimeRegistrationGroup.Key first = group.register(registration("first"));
        AgentRuntimeRegistrationGroup.Key second = group.register(registration("second"));
        assertTrue(group.markAvailable(first));
        assertTrue(group.markAvailable(second));

        assertTrue(group.unregister(first));
        assertFalse(group.unregister(first));
        assertEquals(1, group.size());
        assertTrue(group.isReady());
        assertEquals(AgentRuntimeRegistrationReadiness.State.READY, group.states().get(second));
        group.close();
        assertThrows(IllegalStateException.class, () -> group.register(registration("third")));
    }

    @Test
    void rejectsDuplicateRegistrationIdentity() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(mock(ScheduledFuture.class));
        when(store.acquire(any())).thenAnswer(invocation -> {
            AgentRuntimeRegistrationRequest request = invocation.getArgument(0);
            return Optional.of(lease(token(request), AgentRuntimeStatus.STARTING));
        });

        AgentRuntimeRegistrationGroup group = new AgentRuntimeRegistrationGroup(
                new AgentRuntimeRegistrationLifecycle(store, TTL), scheduler);
        group.register(registration("duplicate"));
        assertThrows(IllegalStateException.class, () -> group.register(registration("duplicate")));
        assertEquals(1, group.size());
        group.close();
    }

    private static AgentRuntimeRegistrationHost.Registration registration(String agentId) {
        return new AgentRuntimeRegistrationHost.Registration(
                "tenant-a", agentId, "instance-a", "owner-" + agentId,
                Duration.ofSeconds(5), Duration.ofMillis(100), Duration.ofSeconds(2));
    }

    private static AgentRuntimeLeaseToken token(AgentRuntimeRegistrationRequest request) {
        return new AgentRuntimeLeaseToken(request.tenantId(), request.agentId(),
                request.instanceId(), request.ownerId(), 1);
    }

    private static AgentRuntimeLease lease(AgentRuntimeLeaseToken token, AgentRuntimeStatus status) {
        return new AgentRuntimeLease(token, status, Instant.now().plus(TTL));
    }
}
