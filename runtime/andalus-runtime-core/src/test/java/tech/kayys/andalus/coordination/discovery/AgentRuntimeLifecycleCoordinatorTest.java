package tech.kayys.andalus.coordination.discovery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.kayys.andalus.execution.mobility.ExecutionLease;
import tech.kayys.andalus.execution.mobility.FencedLeaseManager;
import tech.kayys.andalus.execution.mobility.FencingToken;

class AgentRuntimeLifecycleCoordinatorTest {

    @SuppressWarnings("unchecked")
    @Test
    void transitionsAdvanceGenerationWhileHealthyHeartbeatsRetainIt() {
        Instance<FencedLeaseManager> instances = mock(Instance.class);
        Event<AgentRuntimeLifecycleEvent> events = mock(Event.class);
        TestLeaseManager manager = new TestLeaseManager();
        when(instances.isUnsatisfied()).thenReturn(false);
        when(instances.isAmbiguous()).thenReturn(false);
        when(instances.get()).thenReturn(manager);
        var coordinator = new AgentRuntimeLifecycleCoordinator(
                instances, events, Duration.ofSeconds(30));

        var started = coordinator.transition("agent-a", "tenant-a", "host-a",
                AgentRuntimeLifecycleEvent.Type.STARTED, 1, 1.0);
        var healthy = coordinator.transition("agent-a", "tenant-a", "host-a",
                AgentRuntimeLifecycleEvent.Type.HEALTHY, 1, 1.0);
        var heartbeat = coordinator.renewHealthy("agent-a", "tenant-a", "host-a", 1, 1.0);
        var stopped = coordinator.transition("agent-a", "tenant-a", "host-a",
                AgentRuntimeLifecycleEvent.Type.STOPPED, 1, 1.0);

        assertEquals(1, started.generation());
        assertEquals(2, healthy.generation());
        assertEquals(healthy.generation(), heartbeat.generation());
        assertEquals(3, stopped.generation());
        assertTrue(manager.getLease(AgentRuntimeLifecycleCoordinator.leaseKey(
                "tenant-a", "agent-a")).isEmpty());

        ArgumentCaptor<AgentRuntimeLifecycleEvent> captor =
                ArgumentCaptor.forClass(AgentRuntimeLifecycleEvent.class);
        verify(events, times(4)).fire(captor.capture());
        assertEquals(AgentRuntimeLifecycleEvent.Type.STOPPED,
                captor.getAllValues().get(3).type());
    }

    @SuppressWarnings("unchecked")
    @Test
    void refusesToPublishWhenLeaseAuthorityIsUnavailable() {
        Instance<FencedLeaseManager> instances = mock(Instance.class);
        Event<AgentRuntimeLifecycleEvent> events = mock(Event.class);
        when(instances.isUnsatisfied()).thenReturn(true);
        var coordinator = new AgentRuntimeLifecycleCoordinator(
                instances, events, Duration.ofSeconds(30));

        assertThrows(IllegalStateException.class, () -> coordinator.transition(
                "agent-a", "tenant-a", "host-a",
                AgentRuntimeLifecycleEvent.Type.STARTED, 1, 1.0));
        verifyNoInteractions(events);
    }

    @Test
    void tenantAndAgentLeaseKeysCannotCollide() {
        assertNotEquals(
                AgentRuntimeLifecycleCoordinator.leaseKey("ab", "c"),
                AgentRuntimeLifecycleCoordinator.leaseKey("a", "bc"));
    }

    private static final class TestLeaseManager implements FencedLeaseManager {
        private final Map<String, ExecutionLease> leases = new HashMap<>();
        private final Map<String, Long> generations = new HashMap<>();

        @Override
        public synchronized ExecutionLease acquireLease(String id, String holder, Duration ttl) {
            ExecutionLease current = leases.get(id);
            if (current != null && !current.isExpired() && !current.holderId().equals(holder)) {
                throw new IllegalStateException("owned by another holder");
            }
            long generation = generations.getOrDefault(id, 0L) + 1;
            generations.put(id, generation);
            var lease = new ExecutionLease(id, holder, FencingToken.of(generation),
                    Instant.now(), Instant.now().plus(ttl));
            leases.put(id, lease);
            return lease;
        }

        @Override
        public synchronized boolean renewLease(String id, String holder, Duration ttl) {
            ExecutionLease current = leases.get(id);
            return current != null && renewLease(id, holder, current.fencingToken(), ttl);
        }

        @Override
        public synchronized boolean renewLease(
                String id, String holder, FencingToken token, Duration ttl) {
            ExecutionLease current = leases.get(id);
            if (current == null || current.isExpired() || !current.holderId().equals(holder)
                    || !current.fencingToken().equals(token)) return false;
            leases.put(id, new ExecutionLease(id, holder, token,
                    current.acquiredAt(), Instant.now().plus(ttl)));
            return true;
        }

        @Override
        public synchronized boolean releaseLease(String id, String holder, FencingToken token) {
            ExecutionLease current = leases.get(id);
            if (current == null || !current.holderId().equals(holder)
                    || !current.fencingToken().equals(token)) return false;
            leases.remove(id);
            return true;
        }

        @Override
        public synchronized void releaseLease(String id, String holder) {
            ExecutionLease current = leases.get(id);
            if (current != null && current.holderId().equals(holder)) leases.remove(id);
        }

        @Override
        public synchronized boolean validateToken(String id, FencingToken token) {
            ExecutionLease current = leases.get(id);
            return current != null && current.isValidFor(current.holderId(), token);
        }

        @Override
        public synchronized Optional<FencingToken> currentToken(String id) {
            Long generation = generations.get(id);
            return generation == null ? Optional.empty() : Optional.of(FencingToken.of(generation));
        }

        @Override
        public synchronized Optional<ExecutionLease> getLease(String id) {
            ExecutionLease current = leases.get(id);
            if (current == null || current.isExpired()) {
                leases.remove(id);
                return Optional.empty();
            }
            return Optional.of(current);
        }
    }
}
