package tech.kayys.andalus.coordination.discovery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    @Test
    @SuppressWarnings("unchecked")
    void blockedDatabasePublicationDoesNotHoldGroupMembershipLock() throws Exception {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(mock(ScheduledFuture.class));
        when(store.acquire(any())).thenAnswer(invocation -> {
            AgentRuntimeRegistrationRequest request = invocation.getArgument(0);
            return Optional.of(lease(token(request), AgentRuntimeStatus.STARTING));
        });

        CountDownLatch publicationEntered = new CountDownLatch(1);
        CountDownLatch allowPublication = new CountDownLatch(1);
        when(store.publishStatus(any(), any(), any())).thenAnswer(invocation -> {
            publicationEntered.countDown();
            assertTrue(allowPublication.await(5, TimeUnit.SECONDS),
                    "test must release the blocked status publication");
            return Optional.of(lease(invocation.getArgument(0), invocation.getArgument(1)));
        });
        when(store.revoke(any())).thenReturn(true);

        AgentRuntimeRegistrationGroup group = new AgentRuntimeRegistrationGroup(
                new AgentRuntimeRegistrationLifecycle(store, TTL), scheduler);
        AgentRuntimeRegistrationGroup.Key key = group.register(registration("blocked"));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            var publication = caller.submit(() -> group.markAvailable(key));
            assertTrue(publicationEntered.await(2, TimeUnit.SECONDS),
                    "status publication should reach the store");
            assertEquals(1, group.size(),
                    "membership queries must not wait for an unrelated database operation");
            allowPublication.countDown();
            assertTrue(publication.get(2, TimeUnit.SECONDS));
        } finally {
            allowPublication.countDown();
            caller.shutdownNow();
            group.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void closeRacingRegistrationCleansUpTheReservedSupervisor() throws Exception {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(mock(ScheduledFuture.class));

        CountDownLatch acquireEntered = new CountDownLatch(1);
        CountDownLatch allowAcquire = new CountDownLatch(1);
        when(store.acquire(any())).thenAnswer(invocation -> {
            AgentRuntimeRegistrationRequest request = invocation.getArgument(0);
            acquireEntered.countDown();
            assertTrue(allowAcquire.await(5, TimeUnit.SECONDS),
                    "test must release the blocked registration acquisition");
            return Optional.of(lease(token(request), AgentRuntimeStatus.STARTING));
        });
        when(store.revoke(any())).thenReturn(true);

        AgentRuntimeRegistrationGroup group = new AgentRuntimeRegistrationGroup(
                new AgentRuntimeRegistrationLifecycle(store, TTL), scheduler);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            var registration = callers.submit(() -> group.register(registration("racing")));
            assertTrue(acquireEntered.await(2, TimeUnit.SECONDS),
                    "registration should reach the persistence boundary");

            var shutdown = callers.submit(group::close);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (group.size() != 0 && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(0, group.size(), "shutdown should detach reserved membership");

            allowAcquire.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> registration.get(3, TimeUnit.SECONDS));
            shutdown.get(3, TimeUnit.SECONDS);
            assertEquals(0, group.size());
            verify(store, times(1)).revoke(any());
            assertThrows(IllegalStateException.class, () -> group.register(registration("after-close")));
        } finally {
            allowAcquire.countDown();
            callers.shutdownNow();
            group.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void unregisterRacingAvailabilityPublicationEndsWithFencedRevocation() throws Exception {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(mock(ScheduledFuture.class));
        when(store.acquire(any())).thenAnswer(invocation -> {
            AgentRuntimeRegistrationRequest request = invocation.getArgument(0);
            return Optional.of(lease(token(request), AgentRuntimeStatus.STARTING));
        });

        CountDownLatch publicationEntered = new CountDownLatch(1);
        CountDownLatch allowPublication = new CountDownLatch(1);
        when(store.publishStatus(any(), any(), any())).thenAnswer(invocation -> {
            publicationEntered.countDown();
            assertTrue(allowPublication.await(5, TimeUnit.SECONDS),
                    "test must release the in-flight availability publication");
            return Optional.of(lease(invocation.getArgument(0), invocation.getArgument(1)));
        });
        when(store.revoke(any())).thenReturn(true);

        AgentRuntimeRegistrationGroup group = new AgentRuntimeRegistrationGroup(
                new AgentRuntimeRegistrationLifecycle(store, TTL), scheduler);
        AgentRuntimeRegistrationGroup.Key key = group.register(registration("unregister-race"));
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            var publication = callers.submit(() -> group.markAvailable(key));
            assertTrue(publicationEntered.await(2, TimeUnit.SECONDS));
            var removal = callers.submit(() -> group.unregister(key));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (group.size() != 0 && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(0, group.size(), "unregister removes membership before waiting for supervisor");
            allowPublication.countDown();

            assertTrue(publication.get(3, TimeUnit.SECONDS));
            assertTrue(removal.get(3, TimeUnit.SECONDS));
            assertFalse(group.markAvailable(key), "detached registrations must no longer be addressable");
            verify(store, times(1)).revoke(any());
        } finally {
            allowPublication.countDown();
            callers.shutdownNow();
            group.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void reconcilesOneAgentAfterSchedulerRestorationWithoutDisturbingHealthySibling() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        java.util.List<Runnable> heartbeats = new java.util.ArrayList<>();
        java.util.Map<String, java.util.concurrent.atomic.AtomicInteger> generations = new java.util.HashMap<>();

        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    heartbeats.add(invocation.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenThrow(new IllegalStateException("scheduler temporarily unavailable"));
        when(store.acquire(any())).thenAnswer(invocation -> {
            AgentRuntimeRegistrationRequest request = invocation.getArgument(0);
            int generation = generations.computeIfAbsent(request.agentId(),
                    ignored -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            AgentRuntimeLeaseToken token = new AgentRuntimeLeaseToken(request.tenantId(),
                    request.agentId(), request.instanceId(), request.ownerId(), generation);
            return Optional.of(lease(token, AgentRuntimeStatus.STARTING));
        });
        when(store.publishStatus(any(), any(), any())).thenAnswer(invocation ->
                Optional.of(lease(invocation.getArgument(0), invocation.getArgument(1))));
        when(store.renew(any(), any())).thenAnswer(invocation ->
                Optional.of(lease(invocation.getArgument(0), AgentRuntimeStatus.AVAILABLE)));
        when(store.revoke(any())).thenReturn(true);

        AgentRuntimeRegistrationGroup group = new AgentRuntimeRegistrationGroup(
                new AgentRuntimeRegistrationLifecycle(store, TTL), scheduler);
        AgentRuntimeRegistrationGroup.Key research = group.register(registration("research"));
        AgentRuntimeRegistrationGroup.Key planner = group.register(registration("planner"));
        assertTrue(group.markAvailable(research));
        assertTrue(group.markAvailable(planner));
        assertTrue(group.isReady());

        // Only research loses ownership; recovery scheduling is rejected.
        heartbeats.get(0).run();
        assertFalse(group.isReady());

        // Host repairs its scheduler before asking the selected agent to reconcile.
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    heartbeats.add(invocation.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        assertTrue(group.reconcile(research));
        assertTrue(group.isReady());
        assertEquals(2, generations.get("research").get());
        assertEquals(1, generations.get("planner").get(),
                "healthy sibling must not acquire a replacement generation");
        verify(store, times(2)).acquire(argThat(request -> request.agentId().equals("research")));
        verify(store, times(1)).acquire(argThat(request -> request.agentId().equals("planner")));

        group.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reconcileAllReturnsIndependentResultsAndLeavesHealthyRegistrationsUntouched() {
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

        java.util.Map<AgentRuntimeRegistrationGroup.Key, Boolean> results = group.reconcileAll();

        assertEquals(java.util.Map.of(first, true, second, true), results);
        assertThrows(UnsupportedOperationException.class, () -> results.put(first, false));
        verify(store, times(2)).acquire(any());
        assertTrue(group.isReady());
        group.close();
    }

    @Test
    void reconciliationOfUnknownOrDetachedAgentReturnsFalse() {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        AgentRuntimeRegistrationGroup group = new AgentRuntimeRegistrationGroup(
                new AgentRuntimeRegistrationLifecycle(store, TTL), scheduler);

        AgentRuntimeRegistrationGroup.Key missing =
                new AgentRuntimeRegistrationGroup.Key("tenant-a", "missing", "instance-a");
        assertFalse(group.reconcile(missing));
        assertTrue(group.reconcileAll().isEmpty());
        group.close();
        verify(store, never()).acquire(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnosticsAggregateCurrentReadinessWithoutExposingRegistrationIdentity() {
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

        var starting = group.diagnostics();
        assertEquals(2, starting.total());
        assertEquals(0, starting.ready());
        assertEquals(2, starting.starting());
        assertFalse(starting.isReady());

        assertTrue(group.markAvailable(first));
        assertTrue(group.markAvailable(second));
        var ready = group.diagnostics();
        assertEquals(2, ready.ready());
        assertEquals(0, ready.degraded());
        assertTrue(ready.isReady());

        assertTrue(group.markUnavailable(first));
        var degraded = group.diagnostics();
        assertEquals(1, degraded.ready());
        assertEquals(1, degraded.degraded());
        assertFalse(degraded.isReady());
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
