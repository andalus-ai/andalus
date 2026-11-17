package tech.kayys.andalus.coordination.discovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import tech.kayys.andalus.agent.capability.AgentDirectory;
import tech.kayys.andalus.agent.capability.AgentProfile;
import tech.kayys.andalus.agent.capability.AgentRuntimeLease;
import tech.kayys.andalus.agent.capability.AgentRuntimeRegistrationStore;
import tech.kayys.andalus.agent.capability.AgentRuntimeStatus;
import tech.kayys.andalus.coordination.CoordinationContext;
import tech.kayys.andalus.coordination.CoordinationRequest;

/**
 * Discovers tenant-visible agents only when at least one shared runtime
 * registration is currently AVAILABLE.
 *
 * <p>The registration store is authoritative for liveness/readiness. This
 * source intentionally does not use process-local state as a fallback: a
 * missing, expired, STARTING, UNAVAILABLE, or REVOKED registration is not
 * routable. Multiple live instances collapse to one logical-agent candidate.
 * Invocation authorization remains the dispatcher's responsibility.</p>
 */
@ApplicationScoped
public class RegisteredAgentCapabilitySource implements AgentCapabilitySource {

    private final List<AgentDirectory> directories;
    private final AgentRuntimeRegistrationStore registrations;

    @Inject
    public RegisteredAgentCapabilitySource(
            Instance<AgentDirectory> directories,
            Instance<AgentRuntimeRegistrationStore> registrations) {
        this(toList(directories), toList(registrations));
    }

    RegisteredAgentCapabilitySource(
            List<AgentDirectory> directories,
            List<AgentRuntimeRegistrationStore> registrations) {
        this.directories = List.copyOf(Objects.requireNonNull(directories, "directories"));
        List<AgentRuntimeRegistrationStore> stores =
                List.copyOf(Objects.requireNonNull(registrations, "registrations"));
        if (stores.size() != 1) {
            throw new IllegalArgumentException(
                    "Exactly one AgentRuntimeRegistrationStore must be configured; found " + stores.size());
        }
        this.registrations = stores.getFirst();
    }

    @Override
    public List<AgentCapabilityCandidate> discover(
            CoordinationRequest request, CoordinationContext context) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "context must not be null");
        if (context.tenantId() == null || context.tenantId().isBlank()) {
            return List.of();
        }
        if (directories.size() != 1) {
            throw new IllegalStateException(
                    "Exactly one AgentDirectory must be configured; found " + directories.size());
        }

        List<AgentCapabilityCandidate> candidates = new ArrayList<>();
        for (AgentProfile profile : directories.getFirst().findVisibleToTenant(context.tenantId())) {
            List<AgentRuntimeLease> active = registrations.findActive(context.tenantId(), profile.id());
            boolean available = active.stream()
                    .anyMatch(lease -> lease.status() == AgentRuntimeStatus.AVAILABLE);
            if (!available) {
                continue;
            }
            var capabilityIds = profile.capabilities().stream()
                    .map(capability -> capability.id())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            candidates.add(new AgentCapabilityCandidate(profile.id(), capabilityIds, true, 0, 0));
        }
        return candidates.stream()
                .sorted(Comparator.comparing(AgentCapabilityCandidate::agentId))
                .toList();
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
