package tech.kayys.andalus.workflow.catalog;

import io.quarkus.security.identity.SecurityIdentity;

/**
 * Bridges the authenticated HTTP identity and the runtime's trusted tenant
 * and authorization state into catalog visibility facts.
 *
 * Implementations must resolve tenant membership and grants from trusted
 * server-side state. Never derive them from query parameters or request JSON.
 */
public interface CatalogAccessContextResolver {

    CatalogAccessContext resolve(SecurityIdentity identity);
}
