# AND-5.3.4 — Runtime Capability Discovery

## Scope

This slice connects the framework-owned workflow node descriptor model to runtime providers. It does not add a second descriptor model, execute plugins, or expose an unauthenticated HTTP endpoint.

Framework contracts are defined in `andalus-schema-core`:

- `NodeCapabilityCatalog`
- `NodeTypeDescriptor`
- `NodePortDescriptor`
- `NodeCapabilityCatalogValidator`

Runtime discovery lives in `andalus-workflow-runtime`.

## Runtime contracts

- `NodeCapabilityProvider` is the runtime extension point. A provider contributes a stable provider ID and a collection of framework `NodeTypeDescriptor` instances. Its context-aware discovery hook receives the trusted access context so providers can apply tenant- or subject-specific availability rules.
- `NodeCapabilityCatalogService` aggregates CDI-discovered providers and explicitly registered providers.
- `NodeCapabilityCatalogQuery` supports text, category, and provider filters.
- `CatalogAccessContext` carries tenant/subject identity, trusted permission grants, available runtime capabilities, and the runtime framework version.

Example provider:

```java
@ApplicationScoped
public class CoreNodeCapabilityProvider implements NodeCapabilityProvider {
    @Override
    public String getProviderId() {
        return "andalus.core";
    }

    @Override
    public Collection<NodeTypeDescriptor> getNodeTypes() {
        return List.of(/* framework NodeTypeDescriptor values */);
    }
}
```

The runtime service discovers CDI providers automatically. Every contributed node descriptor must declare the same non-empty provider ID as its provider. Infrastructure may also register/unregister providers explicitly; duplicate provider IDs are rejected. Catalog output is deterministic by category, display name, and type ID. Invalid or conflicting descriptors fail the catalog request rather than being silently omitted.

## Authorization boundary

The authenticated API/resource layer must construct `CatalogAccessContext` from trusted tenant identity and authorization state. The designer client must never supply its own granted permissions, available capabilities, or framework version.

A descriptor is visible only when the access context contains every required permission and every required runtime capability. Missing grants fail closed. Descriptors with framework compatibility bounds are hidden when the trusted runtime version is missing or outside the declared range.

This is catalog visibility filtering, not an authorization grant. The runtime must independently authorize node configuration, workflow publication, and execution. Hiding a node in the designer is never a security control.

## Compatibility

Compatibility bounds currently use dotted numeric versions (for example, `1.2.0`). Comparison is numeric by component; malformed values are rejected rather than interpreted lexically. Pre-release version semantics are intentionally not inferred in this slice.

## Non-goals and follow-up

- REST/GraphQL endpoint and request identity integration.
- Plugin lifecycle and remote provider discovery.
- Configuration JSON Schema evaluation and port-to-port type compatibility checks.
- Catalog caching, ETags, pagination, and change notifications.
- Audit events for provider registration and catalog access.

The service returns metadata snapshots only. It does not instantiate plugin implementations from descriptor data or load executable code from catalog entries.
