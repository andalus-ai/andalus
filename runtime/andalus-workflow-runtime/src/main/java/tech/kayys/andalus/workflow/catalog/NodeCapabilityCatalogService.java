package tech.kayys.andalus.workflow.catalog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import tech.kayys.andalus.schema.workflow.catalog.NodeCapabilityCatalog;
import tech.kayys.andalus.schema.workflow.catalog.NodeCapabilityCatalogValidator;
import tech.kayys.andalus.schema.workflow.catalog.NodeTypeDescriptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aggregates CDI-discovered and explicitly registered node capability
 * providers into caller-authorized catalog snapshots.
 *
 * The service exposes metadata only. It never loads or executes provider code.
 */
@ApplicationScoped
public class NodeCapabilityCatalogService {

    @Inject
    Instance<NodeCapabilityProvider> discoveredProviders;

    private final Map<String, NodeCapabilityProvider> registeredProviders = new ConcurrentHashMap<>();
    private final AtomicLong catalogRevision = new AtomicLong();
    private final NodeCapabilityCatalogValidator validator = new NodeCapabilityCatalogValidator();

    /**
     * Registers a provider. Duplicate provider IDs are rejected to avoid
     * nondeterministic catalog contents.
     */
    public void register(NodeCapabilityProvider provider) {
        Objects.requireNonNull(provider, "provider");
        String providerId = requireText(provider.getProviderId(), "providerId");
        NodeCapabilityProvider existing = registeredProviders.putIfAbsent(providerId, provider);
        if (existing != null && existing != provider) {
            throw new IllegalStateException("A node capability provider is already registered: " + providerId);
        }
        catalogRevision.incrementAndGet();
    }

    /** Removes an explicitly registered provider. CDI-discovered beans remain available. */
    public boolean unregister(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return false;
        }
        boolean removed = registeredProviders.remove(providerId) != null;
        if (removed) {
            catalogRevision.incrementAndGet();
        }
        return removed;
    }

    /**
     * Returns a filtered snapshot. The access context must be derived by the
     * authenticated runtime boundary; missing grants fail closed.
     */
    public NodeCapabilityCatalog discover(NodeCapabilityCatalogQuery query, CatalogAccessContext access) {
        Objects.requireNonNull(access, "access context must be supplied by the authenticated runtime boundary");
        NodeCapabilityCatalogQuery effectiveQuery = query == null ? new NodeCapabilityCatalogQuery() : query;

        Map<String, NodeCapabilityProvider> providers = collectProviders();
        List<NodeTypeDescriptor> descriptors = new ArrayList<>();
        for (NodeCapabilityProvider provider : providers.values()) {
            Collection<NodeTypeDescriptor> contributed = provider.getNodeTypes();
            if (contributed == null) {
                continue;
            }
            for (NodeTypeDescriptor descriptor : contributed) {
                if (descriptor == null) {
                    continue;
                }
                String declaredProvider = descriptor.getProviderId();
                if (declaredProvider != null && !declaredProvider.isBlank()
                        && !declaredProvider.equals(provider.getProviderId())) {
                    throw new IllegalStateException("Node type " + descriptor.getTypeId()
                            + " declares provider " + declaredProvider + " but was contributed by "
                            + provider.getProviderId());
                }
                if (isVisibleTo(descriptor, access) && matches(descriptor, effectiveQuery)) {
                    descriptors.add(descriptor);
                }
            }
        }

        descriptors.sort(Comparator.comparing(NodeTypeDescriptor::getCategory,
                        Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(NodeTypeDescriptor::getDisplayName,
                        Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(NodeTypeDescriptor::getTypeId,
                        Comparator.nullsFirst(String.naturalOrder())));

        NodeCapabilityCatalog catalog = new NodeCapabilityCatalog();
        catalog.setCatalogVersion(Long.toString(catalogRevision.get()));
        catalog.setGeneratedAt(Instant.now().toString());
        catalog.setNodeTypes(descriptors);
        List<String> errors = validator.validate(catalog);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid node capability catalog: " + String.join(", ", errors));
        }
        return catalog;
    }

    private Map<String, NodeCapabilityProvider> collectProviders() {
        Map<String, NodeCapabilityProvider> providers = new java.util.TreeMap<>();
        providers.putAll(registeredProviders);
        Instance<NodeCapabilityProvider> discovered = discoveredProviders;
        if (discovered != null && !discovered.isUnsatisfied() && !discovered.isAmbiguous()) {
            for (NodeCapabilityProvider provider : discovered) {
                if (provider == null) {
                    continue;
                }
                String providerId = requireText(provider.getProviderId(), "providerId");
                providers.putIfAbsent(providerId, provider);
            }
        }
        return providers;
    }

    private boolean isVisibleTo(NodeTypeDescriptor descriptor, CatalogAccessContext access) {
        if (!access.grantedPermissions().containsAll(safe(descriptor.getRequiredPermissions()))) {
            return false;
        }
        return access.availableCapabilities().containsAll(safe(descriptor.getRequiredCapabilities()));
    }

    private boolean matches(NodeTypeDescriptor descriptor, NodeCapabilityCatalogQuery query) {
        if (hasText(query.getCategory()) && !query.getCategory().equalsIgnoreCase(descriptor.getCategory())) {
            return false;
        }
        String providerId = descriptor.getProviderId();
        if (hasText(query.getProviderId()) && !query.getProviderId().equals(providerId)) {
            return false;
        }
        if (hasText(query.getFrameworkVersion())) {
            if (!withinFrameworkRange(query.getFrameworkVersion(),
                    descriptor.getMinimumFrameworkVersion(), descriptor.getMaximumFrameworkVersion())) {
                return false;
            }
        }
        if (hasText(query.getText())) {
            String needle = query.getText().trim().toLowerCase(java.util.Locale.ROOT);
            return contains(descriptor.getTypeId(), needle)
                    || contains(descriptor.getDisplayName(), needle)
                    || contains(descriptor.getDescription(), needle)
                    || contains(descriptor.getCategory(), needle);
        }
        return true;
    }

    private boolean withinFrameworkRange(String actual, String minimum, String maximum) {
        if (hasText(minimum) && compareNumericVersions(actual, minimum) < 0) {
            return false;
        }
        return !hasText(maximum) || compareNumericVersions(actual, maximum) <= 0;
    }

    /** Compares dotted numeric versions; malformed versions are rejected, not guessed. */
    private int compareNumericVersions(String left, String right) {
        int[] a = parseVersion(left);
        int[] b = parseVersion(right);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return 0;
    }

    private int[] parseVersion(String value) {
        if (!hasText(value) || !value.matches("\\d+(\\.\\d+)*")) {
            throw new IllegalArgumentException("Framework versions must use dotted numeric form: " + value);
        }
        String[] parts = value.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Framework version component is out of range: " + value, exception);
            }
        }
        return result;
    }

    private List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(needle);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String requireText(String value, String name) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
