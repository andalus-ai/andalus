package tech.kayys.andalus.coordination.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.andalus.agent.AgentRequest;
import tech.kayys.andalus.agent.capability.AgentCapability;
import tech.kayys.andalus.agent.capability.AgentDirectory;
import tech.kayys.andalus.agent.capability.AgentProfile;
import tech.kayys.andalus.agent.capability.AgentRuntimeLease;
import tech.kayys.andalus.agent.capability.AgentRuntimeLeaseToken;
import tech.kayys.andalus.agent.capability.AgentRuntimeRegistrationStore;
import tech.kayys.andalus.agent.capability.AgentRuntimeStatus;
import tech.kayys.andalus.agent.capability.InMemoryAgentDirectory;
import tech.kayys.andalus.coordination.CoordinationContext;
import tech.kayys.andalus.coordination.CoordinationRequest;
import tech.kayys.andalus.core.AgentDefinition;

class RegisteredAgentCapabilitySourceTest {

    @Test
    void onlyReturnsTenantVisibleAgentsWithAnAvailableSharedRegistration() {
        AgentDirectory directory = new InMemoryAgentDirectory();
        directory.register(profile("tenant-a-ready", "tenant-a"));
        directory.register(profile("tenant-a-starting", "tenant-a"));
        directory.register(profile("tenant-b-ready", "tenant-b"));
        directory.register(new AgentProfile("public-ready", "Public", "Public agent",
                Set.of(new AgentCapability("research.search", "Search")),
                Map.of(AgentDirectory.VISIBILITY_METADATA_KEY, AgentDirectory.PUBLIC_VISIBILITY)));

        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        when(store.findActive("tenant-a", "tenant-a-ready"))
                .thenReturn(List.of(lease("tenant-a", "tenant-a-ready", "node-1", AgentRuntimeStatus.AVAILABLE)));
        when(store.findActive("tenant-a", "tenant-a-starting"))
                .thenReturn(List.of(lease("tenant-a", "tenant-a-starting", "node-2", AgentRuntimeStatus.STARTING)));
        when(store.findActive("tenant-a", "public-ready"))
                .thenReturn(List.of(lease("tenant-a", "public-ready", "node-3", AgentRuntimeStatus.AVAILABLE)));

        var source = new RegisteredAgentCapabilitySource(List.of(directory), List.of(store));
        var candidates = source.discover(request(), CoordinationContext.of("tenant-a", "user-1"));

        assertEquals(List.of("public-ready", "tenant-a-ready"),
                candidates.stream().map(AgentCapabilityCandidate::agentId).toList());
        assertEquals(Set.of("research.search"), candidates.getFirst().capabilities());
    }

    @Test
    void collapsesMultipleAvailableInstancesToOneLogicalAgentCandidate() {
        AgentDirectory directory = new InMemoryAgentDirectory();
        directory.register(profile("agent-a", "tenant-a"));
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        when(store.findActive("tenant-a", "agent-a")).thenReturn(List.of(
                lease("tenant-a", "agent-a", "node-1", AgentRuntimeStatus.AVAILABLE),
                lease("tenant-a", "agent-a", "node-2", AgentRuntimeStatus.AVAILABLE)));

        var source = new RegisteredAgentCapabilitySource(List.of(directory), List.of(store));
        assertEquals(1, source.discover(request(), CoordinationContext.of("tenant-a", "user-1")).size());
    }

    @Test
    void missingTenantOrNonAvailableRegistrationFailsClosed() {
        AgentDirectory directory = new InMemoryAgentDirectory();
        directory.register(profile("agent-a", "tenant-a"));
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        when(store.findActive("tenant-a", "agent-a")).thenReturn(List.of(
                lease("tenant-a", "agent-a", "node-1", AgentRuntimeStatus.UNAVAILABLE),
                lease("tenant-a", "agent-a", "node-2", AgentRuntimeStatus.REVOKED)));

        var source = new RegisteredAgentCapabilitySource(List.of(directory), List.of(store));
        assertEquals(List.of(), source.discover(request(), CoordinationContext.of("tenant-a", "user-1")));
        assertEquals(List.of(), source.discover(request(), CoordinationContext.of(null, "user-1")));
    }

    @Test
    void rejectsMissingOrAmbiguousRegistrationStore() {
        assertThrows(IllegalArgumentException.class,
                () -> new RegisteredAgentCapabilitySource(List.of(new InMemoryAgentDirectory()), List.of()));
        AgentRuntimeRegistrationStore store = mock(AgentRuntimeRegistrationStore.class);
        assertThrows(IllegalArgumentException.class, () -> new RegisteredAgentCapabilitySource(
                List.of(new InMemoryAgentDirectory()), List.of(store, store)));
    }

    private static AgentProfile profile(String id, String tenantId) {
        return new AgentProfile(id, id, "Agent",
                Set.of(new AgentCapability("research.search", "Search")),
                Map.of(AgentDirectory.TENANT_ID_METADATA_KEY, tenantId));
    }

    private static AgentRuntimeLease lease(
            String tenant, String agent, String owner, AgentRuntimeStatus status) {
        return new AgentRuntimeLease(
                new AgentRuntimeLeaseToken(tenant, agent, owner, owner, 1),
                status, Instant.parse("2030-01-01T00:00:00Z"));
    }

    private static CoordinationRequest request() {
        return new CoordinationRequest(
                mock(AgentDefinition.class), mock(AgentRequest.class), null, List.of(), Map.of());
    }
}
