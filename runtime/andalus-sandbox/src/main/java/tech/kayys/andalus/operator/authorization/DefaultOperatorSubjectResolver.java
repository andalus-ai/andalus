package tech.kayys.andalus.operator.authorization;

import tech.kayys.andalus.spi.operator.OperatorContext;
import tech.kayys.andalus.spi.operator.authorization.OperatorSubjectResolver;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves operator identity claims from the request context.
 *
 * <p>No role or permission is granted by default. Deployments may register
 * explicit static grants, while request-scoped grants must be populated by a
 * trusted authentication/authorization integration.</p>
 */
public class DefaultOperatorSubjectResolver implements OperatorSubjectResolver {

    private final Set<String> defaultRoles = ConcurrentHashMap.newKeySet();
    private final Set<String> defaultPermissions = ConcurrentHashMap.newKeySet();

    /**
     * Adds an explicit deployment-level permission grant.
     * Prefer request-scoped permissions for multi-tenant deployments.
     */
    public void grantPermission(String permission) {
        if (permission == null || permission.isBlank() || "*".equals(permission)) {
            throw new IllegalArgumentException("A concrete operator permission is required");
        }
        defaultPermissions.add(permission);
    }

    /**
     * Adds an explicit deployment-level role grant.
     * Prefer request-scoped roles for multi-tenant deployments.
     */
    public void grantRole(String role) {
        if (role == null || role.isBlank() || "*".equals(role)) {
            throw new IllegalArgumentException("A concrete operator role is required");
        }
        defaultRoles.add(role);
    }

    @Override
    public String subject(OperatorContext context) {
        return context != null && context.userId() != null && !context.userId().isBlank()
                ? context.userId()
                : "anonymous-operator";
    }

    @Override
    public Set<String> roles(OperatorContext context) {
        if (context != null) {
            var attr = context.attribute("roles");
            if (attr.isPresent() && attr.get() instanceof Set<?> roles) {
                Set<String> resolved = new HashSet<>();
                for (Object role : roles) {
                    if (role != null && !role.toString().isBlank()) {
                        resolved.add(role.toString());
                    }
                }
                return Set.copyOf(resolved);
            }
        }
        return Set.copyOf(defaultRoles);
    }

    @Override
    public Set<String> permissions(OperatorContext context) {
        if (context != null) {
            var attr = context.attribute("permissions");
            if (attr.isPresent() && attr.get() instanceof Set<?> permissions) {
                Set<String> resolved = new HashSet<>();
                for (Object permission : permissions) {
                    if (permission != null && !permission.toString().isBlank()
                            && !"*".equals(permission.toString())) {
                        resolved.add(permission.toString());
                    }
                }
                return Set.copyOf(resolved);
            }
        }
        return Set.copyOf(defaultPermissions);
    }
}
