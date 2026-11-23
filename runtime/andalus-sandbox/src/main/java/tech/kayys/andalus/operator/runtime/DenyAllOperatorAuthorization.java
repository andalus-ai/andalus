package tech.kayys.andalus.operator.runtime;

import tech.kayys.andalus.spi.operator.OperatorAuthorization;
import tech.kayys.andalus.spi.operator.authorization.OperatorAuthorizationRequest;
import tech.kayys.andalus.spi.operator.authorization.OperatorAuthorizationResult;

/**
 * Safe fallback authorization for operator services constructed without an
 * explicit authorization policy.
 *
 * <p>This implementation deliberately denies every operation. Applications
 * must inject a configured {@link OperatorAuthorization} implementation to
 * enable operator access. Use {@link PermissiveOperatorAuthorization} only
 * when permissive behavior is explicitly intended, such as isolated tests.</p>
 */
public final class DenyAllOperatorAuthorization implements OperatorAuthorization {

    @Override
    public OperatorAuthorizationResult authorize(OperatorAuthorizationRequest request) {
        return OperatorAuthorizationResult.deny(
                "OPERATOR_AUTHORIZATION_NOT_CONFIGURED",
                "Operator authorization is not configured; access is denied by default"
        );
    }
}
