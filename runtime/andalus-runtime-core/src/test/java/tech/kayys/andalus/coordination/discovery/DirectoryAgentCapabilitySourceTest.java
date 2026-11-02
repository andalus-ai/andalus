package tech.kayys.andalus.coordination.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.andalus.agent.AgentRequest;
import tech.kayys.andalus.agent.capability.AgentCapability;
import tech.kayys.andalus.agent.capability.AgentDirectory;
import tech.kayys.andalus.agent.capability.AgentProfile;
import tech.kayys.andalus.agent.capability.InMemoryAgentDirectory;
import tech.kayys.andalus.coordination.CoordinationContext;
import tech.kayys.andalus.coordination.CoordinationRequest;
import tech.kayys.andalus.core.AgentDefinition;

class DirectoryAgentCapabilitySourceTest {

    @Test
    void discoversOnlyTenantVisibleProfilesAndEnrichesLiveRuntimeState() {
        AgentDirectory directory = new InMemoryAgentDirectory();
        directory.register(profile("tenant-a-agent", "tenant-a"));
        directory.register(profile("tenant-b-agent", "tenant-b"));
        directory.register(new AgentProfile("public-agent", "Public", "Public agent",
                Set.of(new AgentCapability("research.search", "Search")),
                Map.of(AgentDirectory.VISIBILITY_METADATA_KEY, AgentDirectory.PUBLIC_VISIBILITY)));

        AgentRuntimeStateProvider stateProvider = (agentId, request, context) ->
                Optional.of(new AgentRuntimeState(true, 7, 1.25));
        var source = new DirectoryAgentCapabilitySource(List.of(directory), List.of(stateProvider));

        List<AgentCapabilityCandidate> candidates = source.discover(
                request(), CoordinationContext.of("tenant-a", "user-1"));

        assertEquals(List.of("public-agent", "tenant-a-agent"),
                candidates.stream().map(AgentCapabilityCandidate::agentId).toList());
        assertEquals(Set.of("research.search"), candidates.get(0).capabilities());
        assertEquals(true, candidates.get(0).available());
        assertEquals(7, candidates.get(0).priority());
        assertEquals(1.25, candidates.get(0).costFactor());
    }

    @Test
    void omitsProfilesWithoutLiveRuntimeState() {
        AgentDirectory directory = new InMemoryAgentDirectory();
        directory.register(profile("tenant-a-agent", "tenant-a"));
        var source = new DirectoryAgentCapabilitySource(
                List.of(directory), List.of((agentId, request, context) -> Optional.empty()));

        assertEquals(List.of(), source.discover(
                request(), CoordinationContext.of("tenant-a", "user-1")));
    }

    @Test
    void rejectsAmbiguousDirectoryAndRuntimeStateOwnership() {
        AgentDirectory first = new InMemoryAgentDirectory();
        AgentDirectory second = new InMemoryAgentDirectory();
        var stateProvider = (AgentRuntimeStateProvider) (agentId, request, context) ->
                Optional.of(new AgentRuntimeState(true, 0, 1.0));
        var ambiguousDirectorySource = new DirectoryAgentCapabilitySource(
                List.of(first, second), List.of(stateProvider));
        assertThrows(IllegalStateException.class, () -> ambiguousDirectorySource.discover(
                request(), CoordinationContext.of("tenant-a", "user-1")));

        AgentDirectory directory = new InMemoryAgentDirectory();
        directory.register(profile("tenant-a-agent", "tenant-a"));
        var ambiguousStateSource = new DirectoryAgentCapabilitySource(
                List.of(directory), List.of(stateProvider, stateProvider));
        assertThrows(IllegalStateException.class, () -> ambiguousStateSource.discover(
                request(), CoordinationContext.of("tenant-a", "user-1")));
    }

    @Test
    void returnsEmptyWhenDirectoryOrStateProvidersAreNotConfigured() {
        var noDirectory = new DirectoryAgentCapabilitySource(
                List.of(), List.of((agentId, request, context) -> Optional.empty()));
        var noStateProvider = new DirectoryAgentCapabilitySource(
                List.of(new InMemoryAgentDirectory()), List.of());

        assertEquals(List.of(), noDirectory.discover(
                request(), CoordinationContext.of("tenant-a", "user-1")));
        assertEquals(List.of(), noStateProvider.discover(
                request(), CoordinationContext.of("tenant-a", "user-1")));
    }

    private static AgentProfile profile(String id, String tenantId) {
        return new AgentProfile(id, id, "Tenant agent",
                Set.of(new AgentCapability("research.search", "Search")),
                Map.of(AgentDirectory.TENANT_ID_METADATA_KEY, tenantId));
    }

    private static CoordinationRequest request() {
        return new CoordinationRequest(
                mock(AgentDefinition.class),
                mock(AgentRequest.class),
                null,
                List.of(),
                Map.of());
    }
}
