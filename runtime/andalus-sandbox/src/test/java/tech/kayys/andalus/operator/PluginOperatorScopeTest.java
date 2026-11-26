package tech.kayys.andalus.operator;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.operator.runtime.plugin.DefaultPluginOperatorService;
import tech.kayys.andalus.spi.operator.OperatorAuthorization;
import tech.kayys.andalus.spi.operator.OperatorContext;
import tech.kayys.andalus.spi.operator.authorization.OperatorAuthorizationRequest;
import tech.kayys.andalus.spi.operator.authorization.OperatorAuthorizationResult;
import tech.kayys.andalus.spi.operator.authorization.OperatorScope;
import tech.kayys.andalus.spi.plugin.PluginManager;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PluginOperatorScopeTest {

    @Test
    void pluginOperationsRequestPlatformScope() {
        AtomicReference<OperatorAuthorizationRequest> captured = new AtomicReference<>();
        OperatorAuthorization authorization = request -> {
            captured.set(request);
            return OperatorAuthorizationResult.allow(request.scope());
        };
        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(
                PluginManager.class.getClassLoader(),
                new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> {
                    if ("getPlugins".equals(method.getName())) return java.util.List.of();
                    return null;
                });

        DefaultPluginOperatorService service = new DefaultPluginOperatorService(pluginManager, authorization);
        OperatorContext context = new OperatorContext("tenant-a", "user-a", "corr-a", "req-a", Map.of());

        assertTrue(service.list(context) instanceof tech.kayys.andalus.spi.operator.OperatorResult.Success);
        assertNotNull(captured.get());
        assertEquals(OperatorScope.PLATFORM, captured.get().scope());
    }
}
