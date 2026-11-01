package tech.kayys.andalus.coordination.discovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import tech.kayys.andalus.coordination.CoordinationContext;
import tech.kayys.andalus.coordination.CoordinationRequest;

/**
 * Aggregates agent candidates advertised by CDI-discovered runtime sources.
 */
@ApplicationScoped
public class AgentCapabilityDirectory {

    private final Instance<AgentCapabilitySource> sources;

    @Inject
    public AgentCapabilityDirectory(Instance<AgentCapabilitySource> sources) {
        this.sources = sources;
    }

    /**
     * Discovers candidates for one coordination request.
     *
     * <p>Duplicate agent ids are rejected rather than silently allowing
     * source ordering to determine which metadata wins.</p>
     */
    public List<AgentCapabilityCandidate> discover(
            CoordinationRequest request,
            CoordinationContext context) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }

        List<AgentCapabilityCandidate> candidates = new ArrayList<>();
        if (sources != null) {
            for (AgentCapabilitySource source : sources) {
                if (source == null) {
                    continue;
                }
                List<AgentCapabilityCandidate> discovered = source.discover(request, context);
                if (discovered != null) {
                    candidates.addAll(discovered);
                }
            }
        }

        Set<String> ids = new HashSet<>();
        for (AgentCapabilityCandidate candidate : candidates) {
            if (candidate == null) {
                throw new IllegalStateException("Agent capability source returned a null candidate");
            }
            if (!ids.add(candidate.agentId())) {
                throw new IllegalStateException(
                        "Multiple capability sources advertised agent id '" + candidate.agentId() + "'");
            }
        }

        return candidates.stream()
                .sorted(Comparator.comparing(AgentCapabilityCandidate::agentId))
                .toList();
    }
}
