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


    @Test
    void reportsRecoverySchedulerRejectionSeparatelyFromStoreFailure() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        AgentRuntimeRegistrationLifecycle lifecycle = new AgentRuntimeRegistrationLifecycle(store, TTL);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> heartbeatTask = ArgumentCaptor.forClass(Runnable.class);
        java.util.List<AgentRuntimeRegistrationObserver.Event> events = new java.util.ArrayList<>();
        when(store.acquire(any())).thenReturn(Optional.of(lease(FIRST, AgentRuntimeStatus.STARTING)));
        when(store.publishStatus(FIRST, AgentRuntimeStatus.AVAILABLE, TTL))
                .thenReturn(Optional.of(lease(FIRST, AgentRuntimeStatus.AVAILABLE)));
        when(store.renew(FIRST, TTL)).thenThrow(new IllegalStateException("database unavailable"));
        when(scheduler.scheduleWithFixedDelay(heartbeatTask.capture(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(heartbeat);
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenThrow(new IllegalStateException("scheduler stopping"));

        AgentRuntimeRegistrationSupervisor supervisor = new AgentRuntimeRegistrationSupervisor(
                lifecycle, scheduler, new AgentRuntimeRegistrationReadiness(),
                "tenant-a", "agent-a", "instance-a", "owner-a",
                HEARTBEAT, Duration.ofMillis(100), Duration.ofSeconds(2), events::add);

        assertTrue(supervisor.start());
        assertTrue(supervisor.markAvailable());
        heartbeatTask.getValue().run();

        assertTrue(events.contains(AgentRuntimeRegistrationObserver.Event.RECOVERY_SCHEDULING_REJECTED));
        assertTrue(events.contains(AgentRuntimeRegistrationObserver.Event.RENEWAL_ERROR));
        assertTrue(supervisor.token().isEmpty());
        supervisor.close();
    }

    @Test
    void explicitReconciliationRecoversAfterRecoverySchedulerWasRestored() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        AgentRuntimeRegistrationLifecycle lifecycle = new AgentRuntimeRegistrationLifecycle(store, TTL);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> firstHeartbeat = mock(ScheduledFuture.class);
        ScheduledFuture<?> secondHeartbeat = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> heartbeatTasks = ArgumentCaptor.forClass(Runnable.class);
        java.util.List<AgentRuntimeRegistrationObserver.Event> events = new java.util.ArrayList<>();

        when(store.acquire(any())).thenReturn(
                Optional.of(lease(FIRST, AgentRuntimeStatus.STARTING)),
                Optional.of(lease(SECOND, AgentRuntimeStatus.STARTING)));
        when(store.publishStatus(any(), eq(AgentRuntimeStatus.AVAILABLE), eq(TTL)))
                .thenAnswer(invocation -> Optional.of(lease(
                        invocation.getArgument(0), AgentRuntimeStatus.AVAILABLE)));
        when(store.renew(FIRST, TTL)).thenThrow(new IllegalStateException("database unavailable"));
        when(store.revoke(SECOND)).thenReturn(true);
        when(scheduler.scheduleWithFixedDelay(heartbeatTasks.capture(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(firstHeartbeat, secondHeartbeat);
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenThrow(new IllegalStateException("scheduler temporarily unavailable"));

        AgentRuntimeRegistrationReadiness readiness = new AgentRuntimeRegistrationReadiness();
        AgentRuntimeRegistrationSupervisor supervisor = new AgentRuntimeRegistrationSupervisor(
                lifecycle, scheduler, readiness, "tenant-a", "agent-a", "instance-a", "owner-a",
                HEARTBEAT, Duration.ofMillis(100), Duration.ofSeconds(2), events::add);

        assertTrue(supervisor.start());
        assertTrue(supervisor.markAvailable());
        heartbeatTasks.getAllValues().get(0).run();
        assertEquals(AgentRuntimeRegistrationReadiness.State.DEGRADED, readiness.state());
        assertTrue(supervisor.token().isEmpty());

        // The host has restored the scheduler; reconciliation performs a fresh acquire.
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(secondHeartbeat);
        assertTrue(supervisor.reconcile());
        assertEquals(SECOND, supervisor.token().orElseThrow());
        assertEquals(AgentRuntimeRegistrationReadiness.State.READY, readiness.state());
        assertTrue(supervisor.isReady());
        assertTrue(events.contains(AgentRuntimeRegistrationObserver.Event.RECONCILIATION_SUCCEEDED));
        verify(store, times(2)).acquire(any());
        verify(store, never()).publishStatus(FIRST, AgentRuntimeStatus.AVAILABLE, TTL);

        supervisor.close();
    }

    @Test
    void reconcilingHealthySessionIsIdempotentAndDoesNotAcquireAnotherGeneration() {
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
        assertTrue(supervisor.reconcile());
        assertEquals(FIRST, supervisor.token().orElseThrow());
        verify(store, times(1)).acquire(any());
        supervisor.close();
    }

    @Test
    void reconciliationAfterCloseDoesNotAcquireOrExposeARegistration() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        AgentRuntimeRegistrationLifecycle lifecycle = new AgentRuntimeRegistrationLifecycle(store, TTL);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        AgentRuntimeRegistrationSupervisor supervisor = new AgentRuntimeRegistrationSupervisor(
                lifecycle, scheduler, new AgentRuntimeRegistrationReadiness(),
                "tenant-a", "agent-a", "instance-a", "owner-a",
                HEARTBEAT, Duration.ofMillis(100), Duration.ofSeconds(2));

        supervisor.close();

        assertFalse(supervisor.reconcile());
        assertTrue(supervisor.token().isEmpty());
        verify(store, never()).acquire(any());
    }

    private static AgentRuntimeLease lease(AgentRuntimeLeaseToken token, AgentRuntimeStatus status) {
        return new AgentRuntimeLease(token, status, Instant.parse("2030-01-01T00:00:00Z"));
    }

    @Test
    void schedulerRejectionDuringStartupRevokesTheAcquiredLeaseAndStaysDegraded() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        AgentRuntimeRegistrationLifecycle lifecycle = new AgentRuntimeRegistrationLifecycle(store, TTL);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(store.acquire(any())).thenReturn(Optional.of(lease(FIRST, AgentRuntimeStatus.STARTING)));
        when(store.revoke(FIRST)).thenReturn(true);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new IllegalStateException("scheduler stopping"));
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenThrow(new IllegalStateException("scheduler stopping"));

        AgentRuntimeRegistrationReadiness readiness = new AgentRuntimeRegistrationReadiness();
        AgentRuntimeRegistrationSupervisor supervisor = new AgentRuntimeRegistrationSupervisor(
                lifecycle, scheduler, readiness, "tenant-a", "agent-a", "instance-a", "owner-a",
                HEARTBEAT, Duration.ofMillis(100), Duration.ofSeconds(2));

        assertFalse(supervisor.start());
        assertEquals(AgentRuntimeRegistrationReadiness.State.DEGRADED, readiness.state());
        assertTrue(supervisor.token().isEmpty());
        verify(store).revoke(FIRST);
        verify(scheduler).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        supervisor.close();
    }

    @Test
    void rejectedRecoverySchedulingNeverExposesLostGeneration() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        AgentRuntimeRegistrationLifecycle lifecycle = new AgentRuntimeRegistrationLifecycle(store, TTL);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> heartbeatTask = ArgumentCaptor.forClass(Runnable.class);
        when(store.acquire(any())).thenReturn(Optional.of(lease(FIRST, AgentRuntimeStatus.STARTING)));
        when(store.publishStatus(FIRST, AgentRuntimeStatus.AVAILABLE, TTL))
                .thenReturn(Optional.of(lease(FIRST, AgentRuntimeStatus.AVAILABLE)));
        when(store.renew(FIRST, TTL)).thenThrow(new IllegalStateException("database unavailable"));
        when(scheduler.scheduleWithFixedDelay(heartbeatTask.capture(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(heartbeat);
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenThrow(new IllegalStateException("scheduler stopping"));

        AgentRuntimeRegistrationReadiness readiness = new AgentRuntimeRegistrationReadiness();
        AgentRuntimeRegistrationSupervisor supervisor = new AgentRuntimeRegistrationSupervisor(
                lifecycle, scheduler, readiness, "tenant-a", "agent-a", "instance-a", "owner-a",
                HEARTBEAT, Duration.ofMillis(100), Duration.ofSeconds(2));

        assertTrue(supervisor.start());
        assertTrue(supervisor.markAvailable());
        heartbeatTask.getValue().run();

        assertEquals(AgentRuntimeRegistrationReadiness.State.DEGRADED, readiness.state());
        assertFalse(supervisor.isReady());
        assertTrue(supervisor.token().isEmpty());
        verify(scheduler).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        supervisor.close();
    }

    @Test
    void databaseOutageAndRecoveryAreIsolatedToTheAffectedAgent() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        AgentRuntimeRegistrationLifecycle lifecycle = new AgentRuntimeRegistrationLifecycle(store, TTL);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeatA = mock(ScheduledFuture.class);
        ScheduledFuture<?> heartbeatB = mock(ScheduledFuture.class);
        ScheduledFuture<?> heartbeatARecovered = mock(ScheduledFuture.class);
        ScheduledFuture<?> retryA = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> heartbeatTasks = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> retryTasks = ArgumentCaptor.forClass(Runnable.class);
        AgentRuntimeLeaseToken planner = new AgentRuntimeLeaseToken(
                "tenant-a", "planner", "instance-a", "owner-planner", 4);
        AgentRuntimeLeaseToken researchNext = new AgentRuntimeLeaseToken(
                "tenant-a", "research", "instance-a", "owner-research", 5);
        java.util.concurrent.atomic.AtomicInteger researchAcquisitions = new java.util.concurrent.atomic.AtomicInteger();

        when(store.acquire(any())).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, tech.kayys.andalus.agent.capability.AgentRuntimeRegistrationRequest.class);
            AgentRuntimeLeaseToken token;
            if (request.agentId().equals("research")) {
                token = researchAcquisitions.incrementAndGet() == 1 ? FIRST : researchNext;
            } else {
                token = planner;
            }
            return Optional.of(lease(token, AgentRuntimeStatus.STARTING));
        });
        when(store.publishStatus(any(), eq(AgentRuntimeStatus.AVAILABLE), eq(TTL))).thenAnswer(invocation -> {
            AgentRuntimeLeaseToken token = invocation.getArgument(0);
            return Optional.of(lease(token, AgentRuntimeStatus.AVAILABLE));
        });
        when(store.renew(FIRST, TTL)).thenThrow(new IllegalStateException("database unavailable"));
        when(store.renew(planner, TTL)).thenReturn(Optional.of(lease(planner, AgentRuntimeStatus.AVAILABLE)));
        when(store.revoke(any())).thenReturn(true);
        when(scheduler.scheduleWithFixedDelay(heartbeatTasks.capture(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(heartbeatA, heartbeatB, heartbeatARecovered);
        when(scheduler.schedule(retryTasks.capture(), anyLong(), any(TimeUnit.class))).thenReturn(retryA);

        AgentRuntimeRegistrationReadiness researchReadiness = new AgentRuntimeRegistrationReadiness();
        AgentRuntimeRegistrationReadiness plannerReadiness = new AgentRuntimeRegistrationReadiness();
        AgentRuntimeRegistrationSupervisor research = new AgentRuntimeRegistrationSupervisor(
                lifecycle, scheduler, researchReadiness, "tenant-a", "research", "instance-a", "owner-research",
                HEARTBEAT, Duration.ofMillis(100), Duration.ofSeconds(2));
        AgentRuntimeRegistrationSupervisor plannerSupervisor = new AgentRuntimeRegistrationSupervisor(
                lifecycle, scheduler, plannerReadiness, "tenant-a", "planner", "instance-a", "owner-planner",
                HEARTBEAT, Duration.ofMillis(100), Duration.ofSeconds(2));

        assertTrue(research.start());
        assertTrue(research.markAvailable());
        assertTrue(plannerSupervisor.start());
        assertTrue(plannerSupervisor.markAvailable());

        heartbeatTasks.getAllValues().get(0).run();
        assertFalse(research.isReady());
        assertTrue(research.token().isEmpty());
        assertTrue(plannerSupervisor.isReady());
        assertEquals(planner, plannerSupervisor.token().orElseThrow());

        retryTasks.getValue().run();

        assertTrue(research.isReady());
        assertEquals(researchNext, research.token().orElseThrow());
        assertTrue(plannerSupervisor.isReady());
        assertEquals(planner, plannerSupervisor.token().orElseThrow());
        verify(store, never()).renew(researchNext, TTL);

        research.close();
        plannerSupervisor.close();
    }

}
