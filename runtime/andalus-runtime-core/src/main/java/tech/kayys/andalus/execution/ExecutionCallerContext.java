package tech.kayys.andalus.execution;

import java.util.Set;

/**
 * Trusted caller identity supplied by an authenticated application boundary.
 *
 * <p>Never construct this value from request variables or prompt content.
 * The runtime treats the supplied identity as authoritative and forwards it
 * to execution governance.</p>
 */
public record ExecutionCallerContext(String tenantId, String userId, Set<String> roles) {

    public ExecutionCallerContext {
        tenantId = normalize(tenantId, "tenantId");
        userId = normalize(userId, "userId");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        if (userId == null && !roles.isEmpty()) {
            throw new IllegalArgumentException("roles require an authenticated userId");
        }
        if (roles.stream().anyMatch(role -> role == null || role.isBlank())) {
            throw new IllegalArgumentException("roles must not contain null or blank values");
        }
    }

    public static ExecutionCallerContext anonymous() {
        return new ExecutionCallerContext(null, null, Set.of());
    }

    private static String normalize(String value, String name) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
