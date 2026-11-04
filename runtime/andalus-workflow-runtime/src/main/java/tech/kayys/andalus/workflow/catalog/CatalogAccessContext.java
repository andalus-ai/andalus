package tech.kayys.andalus.workflow.catalog;

import java.util.Set;

/**
 * Authorization facts supplied by the authenticated runtime boundary.
 *
 * Callers must construct this object from trusted identity/policy state, never
 * from request parameters submitted by the designer client.
 */
public record CatalogAccessContext(
        String tenantId,
        String subjectId,
        Set<String> grantedPermissions,
        Set<String> availableCapabilities,
        String frameworkVersion) {

    public CatalogAccessContext {
        grantedPermissions = grantedPermissions == null ? Set.of() : Set.copyOf(grantedPermissions);
        availableCapabilities = availableCapabilities == null ? Set.of() : Set.copyOf(availableCapabilities);
    }

    public static CatalogAccessContext publicAccess(String frameworkVersion) {
        return new CatalogAccessContext(null, null, Set.of(), Set.of(), frameworkVersion);
    }
}
