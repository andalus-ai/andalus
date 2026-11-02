package tech.kayys.andalus.coordination.discovery;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import tech.kayys.andalus.agent.capability.AgentDirectory;
import tech.kayys.andalus.agent.capability.AgentProfile;
import tech.kayys.andalus.coordination.CoordinationContext;
import tech.kayys.andalus.coordination.CoordinationRequest;

/**
 * Projects tenant-visible framework profiles into runtime delegation
 * candidates, enriching them with live state supplied by runtime providers.
 *
 * <p>Profiles without a matching live-state result are omitted. The directory
 * provides discovery visibility; state providers determine current
 * availability and routing economics. Neither grants invocation permission.</p>
 */
@ApplicationScoped
public class DirectoryAgentCapabilitySource implements AgentCapabilitySource {

    private final List<AgentDirectory> directories;
    private final List<AgentRuntimeStateProvider> stateProviders;

    @Inject
    public DirectoryAgentCapabilitySource(
            Instance<AgentDirectory> directories,
            Instance<AgentRuntimeStateProvider> stateProviders) {
        this(toList(directories), toList(stateProviders));
    }

    DirectoryAgentCapabilitySource(
            List<AgentDirectory> directories,
            List<AgentRuntimeStateProvider> stateProviders) {
        this.directories = List.copyOf(directories);
        this.stateProviders = List.copyOf(stateProviders);
    }

    @Override
    public List<AgentCapabilityCandidate> discover(
            CoordinationRequest request,
            CoordinationContext context) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (directories.isEmpty() || stateProviders.isEmpty()) {
            return List.of();
        }
        if (directories.size() != 1) {
            throw new IllegalStateException(
                    "Exactly one AgentDirectory must be configured; found " + directories.size());
        }

        List<AgentCapabilityCandidate> candidates = new ArrayList<>();
        for (AgentProfile profile : directories.get(0).findVisibleToTenant(context.tenantId())) {
            Optional<AgentRuntimeState> state = resolveState(profile.id(), request, context);
            if (state.isEmpty()) {
                continue;
            }

            Set<String> capabilityIds = new HashSet<>();
            profile.capabilities().forEach(capability -> capabilityIds.add(capability.id()));
            AgentRuntimeState runtimeState = state.get();
            candidates.add(new AgentCapabilityCandidate(
                    profile.id(),
                    capabilityIds,
                    runtimeState.available(),
                    runtimeState.priority(),
                    runtimeState.costFactor()));
        }

        return candidates.stream()
                .sorted(java.util.Comparator.comparing(AgentCapabilityCandidate::agentId))
                .toList();
    }

    private Optional<AgentRuntimeState> resolveState(
            String agentId,
            CoordinationRequest request,
            CoordinationContext context) {
        AgentRuntimeState resolved = null;
        for (AgentRuntimeStateProvider provider : stateProviders) {
            Optional<AgentRuntimeState> candidate = provider.resolve(agentId, request, context);
            if (candidate == null) {
                throw new IllegalStateException(
                        "AgentRuntimeStateProvider returned null instead of Optional for '" + agentId + "'");
            }
            if (candidate.isPresent()) {
                if (resolved != null) {
                    throw new IllegalStateException(
                            "Multiple runtime state providers resolved agent '" + agentId + "'");
                }
                resolved = candidate.get();
            }
        }
        return Optional.ofNullable(resolved);
    }

    private static <T> List<T> toList(Instance<T> instances) {
        List<T> values = new ArrayList<>();
        if (instances != null) {
            for (T instance : instances) {
                if (instance != null) {
                    values.add(instance);
                }
            }
        }
        return values;
    }
}
