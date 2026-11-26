package tech.kayys.andalus.operator;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.operator.runtime.DenyAllOperatorAuthorization;
import tech.kayys.andalus.operator.runtime.PermissiveOperatorAuthorization;
import tech.kayys.andalus.operator.runtime.execution.DefaultExecutionOperatorService;
import tech.kayys.andalus.operator.runtime.execution.InMemoryExecutionControl;
import tech.kayys.andalus.spi.operator.OperatorContext;
import tech.kayys.andalus.spi.operator.OperatorResult;
import tech.kayys.andalus.spi.operator.authorization.OperatorAuthorizationRequest;
import tech.kayys.andalus.spi.operator.execution.ExecutionOperatorPermissions;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OperatorAuthorizationDefaultTest {

    @Test
    void denyAllAuthorizationRejectsEveryRequest() {
        var authorization = new DenyAllOperatorAuthorization();
        var context = new OperatorContext("tenant-a", "user-a", "corr-a", "req-a", Map.of());
        var request = OperatorAuthorizationRequest.tenant(
                context, ExecutionOperatorPermissions.READ.id(), "execution", null);

        var result = authorization.authorize(request);

        assertFalse(result.allowed());
        assertEquals("OPERATOR_AUTHORIZATION_NOT_CONFIGURED", result.code());
    }

    @Test
    void executionServiceConvenienceConstructorDeniesByDefault() {
        var service = new DefaultExecutionOperatorService(new InMemoryExecutionControl());
        var context = new OperatorContext("tenant-a", "user-a", "corr-a", "req-a", Map.of());

        OperatorResult<?> result = service.list(context, null);

        assertInstanceOf(OperatorResult.Failure.class, result);
        assertEquals("PERMISSION_DENIED", ((OperatorResult.Failure<?>) result).code());
    }

    @Test
    void permissiveAuthorizationRemainsAvailableOnlyWhenExplicitlySelected() {
        var authorization = new PermissiveOperatorAuthorization();
        var context = new OperatorContext("tenant-a", "user-a", "corr-a", "req-a", Map.of());
        var request = OperatorAuthorizationRequest.tenant(
                context, ExecutionOperatorPermissions.READ.id(), "execution", null);

        assertTrue(authorization.authorize(request).allowed());
    }
}
