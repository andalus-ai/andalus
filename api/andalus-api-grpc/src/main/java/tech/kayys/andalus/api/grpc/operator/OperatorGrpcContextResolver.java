package tech.kayys.andalus.api.grpc.operator;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.andalus.operator.v1.OperatorRequestContext;
import tech.kayys.andalus.spi.operator.OperatorContext;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Resolves operator identity from the authenticated gRPC security context. */
@ApplicationScoped
public class OperatorGrpcContextResolver {

    public OperatorContext resolve(OperatorRequestContext protoCtx) {
        return resolve(protoCtx, null);
    }

    public OperatorContext resolve(OperatorRequestContext protoCtx, SecurityIdentity identity) {
        boolean authenticated = identity != null && !identity.isAnonymous();
        Principal principal = authenticated ? identity.getPrincipal() : null;
        String userId = principal != null ? principal.getName() : null;
        String tenantId = authenticated ? trustedTenant(identity) : null;

        String correlationId = protoCtx != null && !protoCtx.getCorrelationId().isBlank()
                ? protoCtx.getCorrelationId() : UUID.randomUUID().toString();
        String requestId = protoCtx != null && !protoCtx.getRequestId().isBlank()
                ? protoCtx.getRequestId() : UUID.randomUUID().toString();

        Map<String, Object> attributes = new HashMap<>();
        if (protoCtx != null) {
            protoCtx.getAttributesMap().forEach((key, value) -> {
                if (!"tenantId".equals(key) && !"userId".equals(key) && !"roles".equals(key)
                        && !"principalId".equals(key) && !"principalType".equals(key)) {
                    attributes.put(key, value);
                }
            });
        }
        attributes.put("authenticated", authenticated);
        attributes.put("roles", authenticated ? Set.copyOf(identity.getRoles()) : Set.of());
        return new OperatorContext(tenantId, userId, correlationId, requestId, attributes);
    }

    private String trustedTenant(SecurityIdentity identity) {
        Object value = identity.getAttribute("tenantId");
        if (value == null) return null;
        if (!(value instanceof String tenantId) || tenantId.isBlank()) {
            throw new SecurityException("Authenticated identity contains an invalid tenantId attribute");
        }
        return tenantId;
    }
}
