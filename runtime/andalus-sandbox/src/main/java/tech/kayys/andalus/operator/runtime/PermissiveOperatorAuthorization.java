package tech.kayys.andalus.operator.runtime;

import tech.kayys.andalus.spi.operator.OperatorAuthorization;
import tech.kayys.andalus.spi.operator.OperatorContext;
import tech.kayys.andalus.spi.operator.OperatorPermission;
import tech.kayys.andalus.spi.operator.authorization.OperatorAuthorizationRequest;
import tech.kayys.andalus.spi.operator.authorization.OperatorAuthorizationResult;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Explicit permissive authorization guard for operators.
 * Allows all operations unless a permission is explicitly denied. This implementation
 * must be selected deliberately and must not be used as a production default.
 */
public class PermissiveOperatorAuthorization implements OperatorAuthorization {

    private final Set<String> deniedPermissions = ConcurrentHashMap.newKeySet();

    public void deny(OperatorPermission permission) {
        if (permission != null) {
            deniedPermissions.add(permission.name());
        }
    }

    public void allow(OperatorPermission permission) {
        if (permission != null) {
            deniedPermissions.remove(permission.name());
        }
    }

    public void clear() {
        deniedPermissions.clear();
    }

    @Override
    public OperatorAuthorizationResult authorize(OperatorAuthorizationRequest request) {
        if (request != null && (deniedPermissions.contains(request.permission())
                || (request.permission() != null && deniedPermissions.contains(request.permission())))) {
            return OperatorAuthorizationResult.deny("PERMISSION_DENIED", "Permission denied: " + request.permission());
        }
        return OperatorAuthorizationResult.allow(request != null ? request.scope() : null);
    }

    @Override
    public void require(OperatorContext context, OperatorPermission permission) {
        if (permission != null && deniedPermissions.contains(permission.name())) {
            throw new SecurityException("Permission denied: " + permission.name());
        }
    }
}
