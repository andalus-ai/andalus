package tech.kayys.andalus.coordination.discovery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import tech.kayys.andalus.agent.capability.AgentRuntimeLease;
import tech.kayys.andalus.agent.capability.AgentRuntimeLeaseToken;
import tech.kayys.andalus.agent.capability.AgentRuntimeRegistrationStore;
import tech.kayys.andalus.agent.capability.AgentRuntimeStatus;

class AgentRuntimeRegistrationBootstrapTest {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final AgentRuntimeLeaseToken TOKEN =
            new AgentRuntimeLeaseToken("tenant-a", "agent-a", "instance-a", "owner-a", 7);

    @Test
    @SuppressWarnings("unchecked")
    void startsRegistrationAndDelegatesHostReadinessAndShutdown() throws Exception {
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        AgentRuntimeRegistrationLifecycle lifecycle = new AgentRuntimeRegistrationLifecycle(store, TTL);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        AgentRuntimeRegistrationReadiness readiness = new AgentRuntimeRegistrationReadiness();

        AgentRuntimeRegistrationHost host = () -> new AgentRuntimeRegistrationHost.Registration(
                "tenant-a", "agent-a", "instance-a", "owner-a",
                Duration.ofSeconds(5), Duration.ofMillis(100), Duration.ofSeconds(2));
        Instance<AgentRuntimeRegistrationHost> hosts = mock(Instance.class);
        Instance<ScheduledExecutorService> schedulers = mock(Instance.class);
        Instance<AgentRuntimeRegistrationLifecycle> lifecycles = mock(Instance.class);
        Instance<AgentRuntimeRegistrationReadiness> readinessChecks = mock(Instance.class);
        Instance<AgentRuntimeRegistrationTelemetry> telemetry = mock(Instance.class);

        when(hosts.isUnsatisfied()).thenReturn(false);
        when(hosts.isAmbiguous()).thenReturn(false);
        when(hosts.get()).thenReturn(host);
        when(schedulers.isResolvable()).thenReturn(true);
        when(schedulers.get()).thenReturn(scheduler);
        when(lifecycles.isResolvable()).thenReturn(true);
        when(lifecycles.get()).thenReturn(lifecycle);
        when(readinessChecks.isResolvable()).thenReturn(true);
        when(readinessChecks.get()).thenReturn(readiness);
        when(telemetry.isResolvable()).thenReturn(false);

        when(store.acquire(any())).thenReturn(Optional.of(lease(TOKEN, AgentRuntimeStatus.STARTING)));
        when(store.publishStatus(TOKEN, AgentRuntimeStatus.AVAILABLE, TTL))
                .thenReturn(Optional.of(lease(TOKEN, AgentRuntimeStatus.AVAILABLE)));
        when(store.revoke(TOKEN)).thenReturn(true);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(heartbeat);

        AgentRuntimeRegistrationBootstrap bootstrap = new AgentRuntimeRegistrationBootstrap();
        set(bootstrap, "hosts", hosts);
        set(bootstrap, "schedulers", schedulers);
        set(bootstrap, "lifecycles", lifecycles);
        set(bootstrap, "readinessChecks", readinessChecks);
        set(bootstrap, "telemetry", telemetry);

        bootstrap.onStart(null);
        assertEquals(AgentRuntimeRegistrationReadiness.State.STARTING, readiness.state());
        assertTrue(bootstrap.markAvailable());
        assertTrue(bootstrap.isReady());
        assertEquals(TOKEN, bootstrap.token().orElseThrow());

        bootstrap.onStop(null);
        assertEquals(AgentRuntimeRegistrationReadiness.State.STOPPED, readiness.state());
        assertTrue(bootstrap.token().isEmpty());
        verify(store).revoke(TOKEN);
    }

    @Test
    @SuppressWarnings("unchecked")
    void remainsInertWhenHostDoesNotOptIn() throws Exception {
        Instance<AgentRuntimeRegistrationHost> hosts = mock(Instance.class);
        when(hosts.isUnsatisfied()).thenReturn(true);

        AgentRuntimeRegistrationBootstrap bootstrap = new AgentRuntimeRegistrationBootstrap();
        set(bootstrap, "hosts", hosts);

        assertDoesNotThrow(() -> bootstrap.onStart(null));
        assertFalse(bootstrap.markAvailable());
        assertFalse(bootstrap.isReady());
        assertTrue(bootstrap.token().isEmpty());
    }

    @Test
    void registrationConfigurationValidatesIdentityAndRetryBounds() {
        assertThrows(IllegalArgumentException.class, () ->
                new AgentRuntimeRegistrationHost.Registration(
                        " ", "agent", "instance", "owner",
                        Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class, () ->
                new AgentRuntimeRegistrationHost.Registration(
                        "tenant", "agent", "instance", "owner",
                        Duration.ofSeconds(5), Duration.ofSeconds(3), Duration.ofSeconds(2)));
    }

    private static AgentRuntimeLease lease(AgentRuntimeLeaseToken token, AgentRuntimeStatus status) {
        return new AgentRuntimeLease(token, status, Instant.now().plus(TTL));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
