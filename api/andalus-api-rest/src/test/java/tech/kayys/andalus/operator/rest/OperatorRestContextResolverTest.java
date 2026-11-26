package tech.kayys.andalus.operator.rest;

import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class OperatorRestContextResolverTest {

    @Test
    void requestHeadersCannotEstablishTenantOrUserIdentity() {
        OperatorRestContextResolver resolver = new OperatorRestContextResolver();
        resolver.securityIdentity = null;
        resolver.headers = (HttpHeaders) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{HttpHeaders.class},
                (proxy, method, args) -> {
                    if ("getHeaderString".equals(method.getName())) {
                        return switch ((String) args[0]) {
                            case "X-Tenant-ID" -> "spoofed-tenant";
                            case "X-User-ID" -> "spoofed-user";
                            case "X-Correlation-ID" -> "corr-test";
                            case "X-Request-ID" -> "req-test";
                            default -> null;
                        };
                    }
                    return null;
                });

        var context = resolver.resolve();

        assertNull(context.tenantId());
        assertNull(context.userId());
        assertEquals("corr-test", context.correlationId());
        assertEquals("req-test", context.requestId());
        assertEquals(Boolean.FALSE, context.attributes().get("authenticated"));
        assertEquals(java.util.Set.of(), context.attributes().get("roles"));
    }
}
