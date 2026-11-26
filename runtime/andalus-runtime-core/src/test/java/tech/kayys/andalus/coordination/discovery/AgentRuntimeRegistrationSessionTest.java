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
import org.mockito.InOrder;

import tech.kayys.andalus.agent.capability.AgentRuntimeLease;
import tech.kayys.andalus.agent.capability.AgentRuntimeLeaseToken;
import tech.kayys.andalus.agent.capability.AgentRuntimeRegistrationStore;
import tech.kayys.andalus.agent.capability.AgentRuntimeStatus;

class AgentRuntimeRegistrationSessionTest {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final AgentRuntimeLeaseToken TOKEN =
            new AgentRuntimeLeaseToken("tenant-a", "agent-a", "instance-a", "owner-a", 2);

    @Test
    void retainsTokenAndCancelsHeartbeatBeforeFencedShutdown() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        AgentRuntimeRegistrationLifecycle lifecycle = new AgentRuntimeRegistrationLifecycle(store, TTL);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(store.acquire(any())).thenReturn(Optional.of(lease(AgentRuntimeStatus.STARTING)));
        when(store.publishStatus(TOKEN, AgentRuntimeStatus.AVAILABLE, TTL))
                .thenReturn(Optional.of(lease(AgentRuntimeStatus.AVAILABLE)));
        when(store.revoke(TOKEN)).thenReturn(true);
        doReturn(future).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

        AgentRuntimeRegistrationSession session = AgentRuntimeRegistrationSession.open(
                lifecycle, scheduler, "tenant-a", "agent-a", "instance-a", "owner-a",
                Duration.ofSeconds(5)).orElseThrow();

        assertEquals(TOKEN, session.token());
        assertTrue(session.markAvailable());
        assertTrue(session.isRoutable());
        session.close();
        session.close();

        InOrder order = inOrder(future, store);
        order.verify(future).cancel(false);
        order.verify(store).revoke(TOKEN);
        verify(store, times(1)).revoke(TOKEN);
    }

    @Test
    void heartbeatFailureFailsClosedAndPreventsStalePublication() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        AgentRuntimeRegistrationLifecycle lifecycle = new AgentRuntimeRegistrationLifecycle(store, TTL);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        when(store.acquire(any())).thenReturn(Optional.of(lease(AgentRuntimeStatus.STARTING)));
        when(store.renew(TOKEN, TTL)).thenReturn(Optional.empty());
        doReturn(future).when(scheduler).scheduleWithFixedDelay(task.capture(), anyLong(), anyLong(), any(TimeUnit.class));

        AgentRuntimeRegistrationSession session = AgentRuntimeRegistrationSession.open(
                lifecycle, scheduler, "tenant-a", "agent-a", "instance-a", "owner-a",
                Duration.ofSeconds(5)).orElseThrow();
        task.getValue().run();

        assertFalse(session.isRoutable());
        assertFalse(session.markAvailable());
        verify(future).cancel(false);
        session.close();
        verify(store, never()).publishStatus(any(), any(), any());
        verify(store, never()).revoke(any());
    }

    @Test
    void schedulingFailureRevokesAcquiredRegistration() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        AgentRuntimeRegistrationLifecycle lifecycle = new AgentRuntimeRegistrationLifecycle(store, TTL);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(store.acquire(any())).thenReturn(Optional.of(lease(AgentRuntimeStatus.STARTING)));
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new IllegalStateException("scheduler stopping"));
        when(store.revoke(TOKEN)).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> AgentRuntimeRegistrationSession.open(
                lifecycle, scheduler, "tenant-a", "agent-a", "instance-a", "owner-a",
                Duration.ofSeconds(5)));
        verify(store).revoke(TOKEN);
    }

    @Test
    void observerDistinguishesRejectedRenewalFromStoreFailure() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        AgentRuntimeRegistrationLifecycle lifecycle = new AgentRuntimeRegistrationLifecycle(store, TTL);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        java.util.List<AgentRuntimeRegistrationObserver.Event> events = new java.util.ArrayList<>();
        when(store.acquire(any())).thenReturn(Optional.of(lease(AgentRuntimeStatus.STARTING)));
        when(store.renew(TOKEN, TTL)).thenReturn(Optional.empty());
        doReturn(future).when(scheduler).scheduleWithFixedDelay(task.capture(), anyLong(), anyLong(), any(TimeUnit.class));

        AgentRuntimeRegistrationSession session = AgentRuntimeRegistrationSession.open(
                lifecycle, scheduler, "tenant-a", "agent-a", "instance-a", "owner-a",
                Duration.ofSeconds(5), events::add).orElseThrow();
        task.getValue().run();

        assertEquals(java.util.List.of(
                AgentRuntimeRegistrationObserver.Event.ACQUIRED,
                AgentRuntimeRegistrationObserver.Event.RENEWAL_REJECTED), events);
        assertFalse(session.isRoutable());
        session.close();
    }

    @Test
    void observerFailureCannotChangeRegistrationSafety() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        AgentRuntimeRegistrationLifecycle lifecycle = new AgentRuntimeRegistrationLifecycle(store, TTL);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(store.acquire(any())).thenReturn(Optional.of(lease(AgentRuntimeStatus.STARTING)));
        when(store.publishStatus(TOKEN, AgentRuntimeStatus.AVAILABLE, TTL))
                .thenReturn(Optional.of(lease(AgentRuntimeStatus.AVAILABLE)));
        when(store.revoke(TOKEN)).thenReturn(true);
        doReturn(future).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

        AgentRuntimeRegistrationSession session = AgentRuntimeRegistrationSession.open(
                lifecycle, scheduler, "tenant-a", "agent-a", "instance-a", "owner-a",
                Duration.ofSeconds(5), event -> { throw new IllegalStateException("metrics unavailable"); })
                .orElseThrow();

        assertTrue(session.markAvailable());
        assertTrue(session.isRoutable());
        session.close();
        verify(store).revoke(TOKEN);
    }

    @Test
    void observerReportsStoreExceptionSeparatelyFromRejectedLease() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        AgentRuntimeRegistrationLifecycle lifecycle = new AgentRuntimeRegistrationLifecycle(store, TTL);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        java.util.List<AgentRuntimeRegistrationObserver.Event> events = new java.util.ArrayList<>();
        when(store.acquire(any())).thenReturn(Optional.of(lease(AgentRuntimeStatus.STARTING)));
        when(store.renew(TOKEN, TTL)).thenThrow(new IllegalStateException("database unavailable"));
        doReturn(future).when(scheduler).scheduleWithFixedDelay(task.capture(), anyLong(), anyLong(), any(TimeUnit.class));

        AgentRuntimeRegistrationSession session = AgentRuntimeRegistrationSession.open(
                lifecycle, scheduler, "tenant-a", "agent-a", "instance-a", "owner-a",
                Duration.ofSeconds(5), events::add).orElseThrow();
        task.getValue().run();

        assertEquals(java.util.List.of(
                AgentRuntimeRegistrationObserver.Event.ACQUIRED,
                AgentRuntimeRegistrationObserver.Event.RENEWAL_ERROR), events);
        assertFalse(session.isRoutable());
        session.close();
    }

    private static AgentRuntimeLease lease(AgentRuntimeStatus status) {
        return new AgentRuntimeLease(TOKEN, status, Instant.parse("2030-01-01T00:00:00Z"));
    }
}
