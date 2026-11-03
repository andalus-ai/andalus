package tech.kayys.andalus.sandbox.runtime;

import tech.kayys.andalus.spi.sandbox.SandboxProvider;
import tech.kayys.andalus.spi.sandbox.SandboxProviderRegistry;
import tech.kayys.andalus.spi.sandbox.SandboxRequest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DefaultSandboxProviderRegistry implements SandboxProviderRegistry {

    private final ConcurrentMap<String, SandboxProvider> providers = new ConcurrentHashMap<>();

    @Override
    public void register(SandboxProvider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        String id = provider.providerId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        providers.put(id, provider);
    }

    @Override
    public void unregister(String providerId) {
        if (providerId != null) {
            providers.remove(providerId);
        }
    }

    @Override
    public Optional<SandboxProvider> find(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(providers.get(providerId));
    }

    @Override
    public List<SandboxProvider> findAll() {
        return List.copyOf(providers.values());
    }

    public SandboxProvider select(SandboxRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        // Try providerId specified in attributes
        if (request.attributes() != null && request.attributes().containsKey("providerId")) {
            String requestedId = String.valueOf(request.attributes().get("providerId"));
            SandboxProvider found = providers.get(requestedId);
            if (found != null) {
                return found;
            }
            throw new IllegalArgumentException("Requested sandbox provider not found: " + requestedId);
        }

        // Search by preferred sandbox type
        for (SandboxProvider provider : providers.values()) {
            if (provider.descriptor() != null && provider.descriptor().supports(request.preferredType())) {
                return provider;
            }
        }

        // Never silently downgrade an isolation request. In particular, a
        // CONTAINER or VM request must not fall back to a local PROCESS provider.
        throw new IllegalStateException(
                "No sandbox provider registered for requested type: " + request.preferredType());
    }
}
