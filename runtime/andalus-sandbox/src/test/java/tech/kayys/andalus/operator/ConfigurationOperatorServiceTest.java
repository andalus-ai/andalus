package tech.kayys.andalus.operator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.operator.audit.DefaultOperatorAuditRedactor;
import tech.kayys.andalus.operator.audit.DefaultOperatorAuditService;
import tech.kayys.andalus.operator.authorization.DefaultOperatorAuthorization;
import tech.kayys.andalus.operator.authorization.DefaultOperatorAuthorizationPolicy;
import tech.kayys.andalus.operator.authorization.DefaultOperatorPermissionRegistry;
import tech.kayys.andalus.operator.authorization.DefaultOperatorSubjectResolver;
import tech.kayys.andalus.operator.runtime.configuration.DefaultConfigurationOperatorService;
import tech.kayys.andalus.spi.operator.OperatorContext;
import tech.kayys.andalus.spi.operator.OperatorResult;
import tech.kayys.andalus.spi.operator.configuration.ConfigurationSummary;
import tech.kayys.andalus.spi.operator.configuration.ConfigurationUpdateRequest;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationOperatorServiceTest {

    private DefaultConfigurationOperatorService service;
    private DefaultOperatorAuditService auditService;
    private OperatorContext context;

    @BeforeEach
    void setUp() {
        var permissionRegistry = new DefaultOperatorPermissionRegistry();
        var subjectResolver = new DefaultOperatorSubjectResolver();
        var policy = new DefaultOperatorAuthorizationPolicy(permissionRegistry, subjectResolver);
        var authorization = new DefaultOperatorAuthorization(policy);
        auditService = new DefaultOperatorAuditService(new DefaultOperatorAuditRedactor());

        service = new DefaultConfigurationOperatorService(authorization, auditService);
        context = new OperatorContext("tenant-a", "user-1", "corr-1", "req-1", Map.of(
                "permissions", Set.of(
                        "operator.configuration.read",
                        "operator.configuration.inspect",
                        "operator.configuration.update",
                        "operator.configuration.activate"
                )
        ));
    }

    @Test
    void testListConfigurations() {
        OperatorResult<?> result = service.list(context);
        assertInstanceOf(OperatorResult.Success.class, result);
        var list = (java.util.List<?>) ((OperatorResult.Success<?>) result).value();
        assertFalse(list.isEmpty());
        assertFalse(auditService.getRecentEvents().isEmpty());
    }

    @Test
    void testInspectMasksSensitiveProperties() {
        OperatorResult<ConfigurationSummary> result = service.inspect(context, "default");
        assertInstanceOf(OperatorResult.Success.class, result);
        var summary = ((OperatorResult.Success<ConfigurationSummary>) result).value();
        assertEquals("default", summary.id());

        // Sensitive properties should be masked to ******
        assertEquals("******", summary.values().get("andalus.security.apiKey"));
        assertEquals("******", summary.values().get("andalus.database.password"));

        // Non-sensitive property remains unmasked
        assertEquals(200, summary.values().get("andalus.runtime.maxThreads"));
    }

    @Test
    void testUpdateAndActivate() {
        var updateReq = new ConfigurationUpdateRequest(
                Map.of("custom.setting", "val-123", "secret.token", "my-secret"),
                true
        );

        OperatorResult<ConfigurationSummary> updateResult = service.update(context, "tenant-cfg-1", updateReq);
        assertInstanceOf(OperatorResult.Success.class, updateResult);

        var updated = ((OperatorResult.Success<ConfigurationSummary>) updateResult).value();
        assertEquals("val-123", updated.values().get("custom.setting"));
        assertEquals("******", updated.values().get("secret.token"));
        assertEquals("ACTIVE", updated.status());

        // Verify active configuration matches
        OperatorResult<ConfigurationSummary> activeResult = service.active(context);
        assertInstanceOf(OperatorResult.Success.class, activeResult);
        assertEquals("tenant-cfg-1", ((OperatorResult.Success<ConfigurationSummary>) activeResult).value().id());

        // Verify audit event recorded update
        boolean hasUpdateAudit = auditService.getRecentEvents().stream()
                .anyMatch(e -> "operator.configuration.update".equals(e.operation()));
        assertTrue(hasUpdateAudit);
    }
}
