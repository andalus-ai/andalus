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
import org.mockito.ArgumentCaptor;

import tech.kayys.andalus.agent.capability.AgentRuntimeLease;
import tech.kayys.andalus.agent.capability.AgentRuntimeLeaseToken;
import tech.kayys.andalus.agent.capability.AgentRuntimeRegistrationStore;
import tech.kayys.andalus.agent.capability.AgentRuntimeStatus;

class AgentRuntimeRegistrationSupervisorTest {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Duration HEARTBEAT = Duration.ofSeconds(5);
    private static final AgentRuntimeLeaseToken FIRST =
            new AgentRuntimeLeaseToken("tenant-a", "agent-a", "instance-a", "owner-a", 2);
    private static final AgentRuntimeLeaseToken SECOND =
            new AgentRuntimeLeaseToken("tenant-a", "agent-a", "instance-a", "owner-a", 3);

    @Test
    void reacquiresFreshGenerationAfterDatabaseFailureAndRestoresDesiredAvailability() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        AgentRuntimeRegistrationLifecycle lifecycle = new AgentRuntimeRegistrationLifecycle(store, TTL);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> firstHeartbeat = mock(ScheduledFuture.class);
        ScheduledFuture<?> secondHeartbeat = mock(ScheduledFuture.class);
        ScheduledFuture<?> retryFuture = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> heartbeatTasks = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> retryTask = ArgumentCaptor.forClass(Runnable.class);

        when(store.acquire(any())).thenReturn(
                Optional.of(lease(FIRST, AgentRuntimeStatus.STARTING)),
                Optional.of(lease(SECOND, AgentRuntimeStatus.STARTING)));
        when(store.publishStatus(FIRST, AgentRuntimeStatus.AVAILABLE, TTL))
                .thenReturn(Optional.of(lease(FIRST, AgentRuntimeStatus.AVAILABLE)));
        when(store.publishStatus(SECOND, AgentRuntimeStatus.AVAILABLE, TTL))
                .thenReturn(Optional.of(lease(SECOND, AgentRuntimeStatus.AVAILABLE)));
        when(store.renew(FIRST, TTL)).thenThrow(new IllegalStateException("database unavailable"));
        when(store.revoke(SECOND)).thenReturn(true);
        when(scheduler.scheduleWithFixedDelay(heartbeatTasks.capture(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(firstHeartbeat, secondHeartbeat);
        when(scheduler.schedule(retryTask.capture(), anyLong(), any(TimeUnit.class)))
                .thenReturn(retryFuture);

        AgentRuntimeRegistrationReadiness readiness = new AgentRuntimeRegistrationReadiness();
        AgentRuntimeRegistrationSupervisor supervisor = new AgentRuntimeRegistrationSupervisor(
                lifecycle, scheduler, readiness, "tenant-a", "agent-a", "instance-a", "owner-a",
                HEARTBEAT, Duration.ofMillis(100), Duration.ofSeconds(2));

        assertTrue(supervisor.start());
        assertEquals(AgentRuntimeRegistrationReadiness.State.STARTING, readiness.state());
        assertTrue(supervisor.markAvailable());
        assertTrue(supervisor.isReady());
        assertEquals(FIRST, supervisor.token().orElseThrow());

        heartbeatTasks.getAllValues().get(0).run();
        assertEquals(AgentRuntimeRegistrationReadiness.State.DEGRADED, readiness.state());
        assertFalse(supervisor.isReady());
        assertTrue(supervisor.token().isEmpty(), "a lost generation must not be exposed as current");

        retryTask.getValue().run();

        assertEquals(SECOND, supervisor.token().orElseThrow());
        assertEquals(AgentRuntimeRegistrationReadiness.State.READY, readiness.state());
        assertTrue(supervisor.isReady());
        verify(store, never()).renew(SECOND, TTL);
        verify(store, never()).publishStatus(FIRST, AgentRuntimeStatus.AVAILABLE, TTL);

        supervisor.close();
        assertEquals(AgentRuntimeRegistrationReadiness.State.STOPPED, readiness.state());
        verify(store).revoke(SECOND);
    }

    @Test
    void readinessIsOptInAndSeparatesStartupFromReady() {
        AgentRuntimeRegistrationReadiness readiness = new AgentRuntimeRegistrationReadiness();

        assertTrue(readiness.isReady());
        assertEquals(AgentRuntimeRegistrationReadiness.State.NOT_CONFIGURED, readiness.state());

        readiness.starting();
        assertFalse(readiness.isReady());
        readiness.ready();
        assertTrue(readiness.isReady());
        readiness.degraded();
        assertFalse(readiness.isReady());
        readiness.stopped();
        assertFalse(readiness.isReady());
    }

    @Test
    void startIsIdempotentWhileARegistrationSessionExists() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        AgentRuntimeRegistrationLifecycle lifecycle = new AgentRuntimeRegistrationLifecycle(store, TTL);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(store.acquire(any())).thenReturn(Optional.of(lease(FIRST, AgentRuntimeStatus.STARTING)));
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(mock(ScheduledFuture.class));

        AgentRuntimeRegistrationSupervisor supervisor = new AgentRuntimeRegistrationSupervisor(
                lifecycle, scheduler, new AgentRuntimeRegistrationReadiness(),
                "tenant-a", "agent-a", "instance-a", "owner-a",
                HEARTBEAT, Duration.ofMillis(100), Duration.ofSeconds(2));

        assertTrue(supervisor.start());
        assertTrue(supervisor.start());
        verify(store, times(1)).acquire(any());
        supervisor.close();
    }

    private static AgentRuntimeLease lease(AgentRuntimeLeaseToken token, AgentRuntimeStatus status) {
        return new AgentRuntimeLease(token, status, Instant.parse("2030-01-01T00:00:00Z"));
    }
}
