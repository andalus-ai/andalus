package tech.kayys.andalus.coordination.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CapabilityBasedAgentSelectorTest {

    private final CapabilityBasedAgentSelector selector = new CapabilityBasedAgentSelector();

    @Test
    void filtersUnavailableMissingCapabilitiesAndOverBudgetCandidates() {
        var requirements = new TaskCapabilityRequirements(Set.of("research.search"), Set.of(), 1.5);
        var candidates = List.of(
                candidate("available", Set.of("research.search"), true, 0, 1.0),
                candidate("offline", Set.of("research.search"), false, 10, 0.5),
                candidate("missing", Set.of("research.write"), true, 10, 0.5),
                candidate("expensive", Set.of("research.search"), true, 10, 2.0));

        assertEquals(List.of("available"), ids(selector.select(requirements, candidates)));
    }

    @Test
    void ranksPreferredCoverageThenPriorityThenCostThenId() {
        var requirements = new TaskCapabilityRequirements(
                Set.of("research.search"), Set.of("research.summarize", "research.cite"), null);
        var candidates = List.of(
                candidate("z-costly", Set.of("research.search", "research.summarize"), true, 2, 2.0),
                candidate("b-priority", Set.of("research.search", "research.summarize"), true, 3, 1.0),
                candidate("a-priority", Set.of("research.search", "research.summarize"), true, 3, 1.0),
                candidate("best", Set.of("research.search", "research.summarize", "research.cite"), true, 0, 9.0));

        assertEquals(List.of("best", "a-priority", "b-priority", "z-costly"),
                ids(selector.select(requirements, candidates)));
    }

    @Test
    void returnsEmptyWhenNoCandidateMatches() {
        assertEquals(List.of(), selector.select(
                TaskCapabilityRequirements.requiring(Set.of("missing.capability")),
                List.of(candidate("agent", Set.of("other.capability"), true, 0, 1.0))));
    }

    @Test
    void rejectsInvalidCostValues() {
        assertThrows(IllegalArgumentException.class,
                () -> candidate("agent", Set.of(), true, 0, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskCapabilityRequirements(Set.of(), Set.of(), -1.0));
    }

    private static AgentCapabilityCandidate candidate(
            String id, Set<String> capabilities, boolean available, int priority, double cost) {
        return new AgentCapabilityCandidate(id, capabilities, available, priority, cost);
    }

    private static List<String> ids(List<AgentCapabilityCandidate> candidates) {
        return candidates.stream().map(AgentCapabilityCandidate::agentId).toList();
    }
}
