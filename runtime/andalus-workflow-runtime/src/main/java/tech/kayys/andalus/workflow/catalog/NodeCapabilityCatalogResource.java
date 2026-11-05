package tech.kayys.andalus.workflow.catalog;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import tech.kayys.andalus.schema.workflow.catalog.NodeCapabilityCatalog;

import java.util.List;

/**
 * Read-only authenticated endpoint for workflow designer node metadata.
 *
 * Tenant and permission facts are resolved by a trusted server-side adapter;
 * the client can only supply presentation/search filters and pagination.
 */
@Path("/api/workflow/node-capabilities")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class NodeCapabilityCatalogResource {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    @Inject
    NodeCapabilityCatalogService catalogService;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    Instance<CatalogAccessContextResolver> accessContextResolvers;

    @GET
    public NodeCapabilityCatalogPage list(
            @QueryParam("q") String text,
            @QueryParam("category") String category,
            @QueryParam("provider") String providerId,
            @QueryParam("offset") Integer requestedOffset,
            @QueryParam("limit") Integer requestedLimit) {

        if (securityIdentity == null || securityIdentity.isAnonymous()) {
            throw new jakarta.ws.rs.NotAuthorizedException("Authentication required");
        }
        if (accessContextResolvers == null || accessContextResolvers.isUnsatisfied()) {
            // Fail closed: an authenticated principal without a configured
            // tenant/policy bridge must not receive an unscoped catalog.
            throw new jakarta.ws.rs.ServiceUnavailableException(
                    "Catalog access context resolver is not configured");
        }

        int offset = requestedOffset == null ? 0 : requestedOffset;
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (offset < 0 || limit < 1 || limit > MAX_LIMIT) {
            throw new BadRequestException("offset must be >= 0 and limit must be between 1 and " + MAX_LIMIT);
        }

        CatalogAccessContext access = accessContextResolvers.get().resolve(securityIdentity);
        if (access == null) {
            throw new jakarta.ws.rs.ForbiddenException("No authorized catalog context is available");
        }

        NodeCapabilityCatalogQuery query = new NodeCapabilityCatalogQuery()
                .setText(text)
                .setCategory(category)
                .setProviderId(providerId);
        NodeCapabilityCatalog snapshot = catalogService.discover(query, access);
        List<tech.kayys.andalus.schema.workflow.catalog.NodeTypeDescriptor> all = snapshot.getNodeTypes();
        int from = Math.min(offset, all.size());
        int to = Math.min(from + limit, all.size());

        return new NodeCapabilityCatalogPage(
                snapshot.getCatalogVersion(),
                snapshot.getGeneratedAt(),
                offset,
                limit,
                all.size(),
                all.subList(from, to));
    }
}
