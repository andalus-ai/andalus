package tech.kayys.andalus.coordination.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AgentCapabilityCandidateTest {

    @Test
    void trimsAgentIdentifierAndCopiesCapabilities() {
        var source = new HashSet<>(Set.of("research.search"));
        var candidate = new AgentCapabilityCandidate(" agent-1 ", source, true, 0, 1.0);

        source.add("research.write");

        assertEquals("agent-1", candidate.agentId());
        assertEquals(Set.of("research.search"), candidate.capabilities());
        assertThrows(UnsupportedOperationException.class,
                () -> candidate.capabilities().add("research.write"));
    }

    @Test
    void rejectsBlankAgentAndCapabilityIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentCapabilityCandidate(" ", Set.of(), true, 0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentCapabilityCandidate("agent-1", Set.of(" "), true, 0, 1.0));
    }

    @Test
    void rejectsCapabilityIdentifiersWithSurroundingWhitespace() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentCapabilityCandidate("agent-1", Set.of(" research.search "), true, 0, 1.0));
    }

    @Test
    void rejectsNullCapabilityEntries() {
        Set<String> capabilities = new HashSet<>();
        capabilities.add(null);

        assertThrows(IllegalArgumentException.class,
                () -> new AgentCapabilityCandidate("agent-1", capabilities, true, 0, 1.0));
    }

    @Test
    void acceptsNullCapabilitySetAsNoCapabilities() {
        var candidate = new AgentCapabilityCandidate("agent-1", null, true, 0, 1.0);

        assertEquals(Set.of(), candidate.capabilities());
    }

    @Test
    void rejectsInvalidCostFactors() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentCapabilityCandidate("agent-1", Set.of(), true, 0, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentCapabilityCandidate("agent-1", Set.of(), true, 0, -0.1));
    }
}
