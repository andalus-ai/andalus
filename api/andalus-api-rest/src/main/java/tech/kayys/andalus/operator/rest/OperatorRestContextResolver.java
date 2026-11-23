package tech.kayys.andalus.operator.rest;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import tech.kayys.andalus.spi.operator.OperatorContext;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Resolves operator identity only from the authenticated Quarkus security identity. */
@RequestScoped
public class OperatorRestContextResolver {

    @Context
    HttpHeaders headers;

    @Inject
    SecurityIdentity securityIdentity;

    public OperatorContext resolve() {
        SecurityIdentity identity = securityIdentity;
        boolean authenticated = identity != null && !identity.isAnonymous() && identity.getPrincipal() != null;
        Principal principal = authenticated ? identity.getPrincipal() : null;
        String userId = principal != null ? principal.getName() : null;
        String tenantId = authenticated ? trustedTenant(identity) : null;

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("authenticated", authenticated);
        attributes.put("roles", authenticated ? Set.copyOf(identity.getRoles()) : Set.of());
        if (headers != null) {
            String userAgent = headers.getHeaderString("User-Agent");
            if (userAgent != null) attributes.put("userAgent", userAgent);
            String forwardedFor = headers.getHeaderString("X-Forwarded-For");
            if (forwardedFor != null) attributes.put("forwardedFor", forwardedFor);
        }

        return new OperatorContext(tenantId, userId,
                getHeader("X-Correlation-ID", UUID.randomUUID().toString()),
                getHeader("X-Request-ID", UUID.randomUUID().toString()), attributes);
    }

    private String trustedTenant(SecurityIdentity identity) {
        Object value = identity.getAttribute("tenantId");
        if (value == null) return null;
        if (!(value instanceof String tenantId) || tenantId.isBlank()) {
            throw new SecurityException("Authenticated identity contains an invalid tenantId attribute");
        }
        return tenantId;
    }

    private String getHeader(String name, String defaultValue) {
        if (headers != null) {
            String value = headers.getHeaderString(name);
            if (value != null && !value.isBlank()) return value;
        }
        return defaultValue;
    }
}
