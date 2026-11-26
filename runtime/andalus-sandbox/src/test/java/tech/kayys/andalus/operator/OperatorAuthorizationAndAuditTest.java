package tech.kayys.andalus.operator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.operator.audit.DefaultOperatorAuditRedactor;
import tech.kayys.andalus.operator.audit.DefaultOperatorAuditService;
import tech.kayys.andalus.operator.authorization.DefaultOperatorAuthorization;
import tech.kayys.andalus.operator.authorization.DefaultOperatorAuthorizationPolicy;
import tech.kayys.andalus.operator.authorization.DefaultOperatorPermissionRegistry;
import tech.kayys.andalus.operator.authorization.DefaultOperatorSubjectResolver;
import tech.kayys.andalus.spi.operator.OperatorAuthorizationException;
import tech.kayys.andalus.spi.operator.OperatorContext;
import tech.kayys.andalus.spi.operator.audit.OperatorAuditOutcome;
import tech.kayys.andalus.spi.operator.authorization.OperatorAuthorizationRequest;
import tech.kayys.andalus.spi.operator.authorization.OperatorScope;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OperatorAuthorizationAndAuditTest {

    private DefaultOperatorPermissionRegistry registry;
    private DefaultOperatorSubjectResolver subjectResolver;
    private DefaultOperatorAuthorizationPolicy policy;
    private DefaultOperatorAuthorization authorization;
    private DefaultOperatorAuditService auditService;

    @BeforeEach
    void setUp() {
        registry = new DefaultOperatorPermissionRegistry();
        subjectResolver = new DefaultOperatorSubjectResolver();
        policy = new DefaultOperatorAuthorizationPolicy(registry, subjectResolver);
        authorization = new DefaultOperatorAuthorization(policy);
        auditService = new DefaultOperatorAuditService(new DefaultOperatorAuditRedactor());
    }

    @Test
    void testPermissionLookup() {
        var opt = registry.find("operator.plugins.read");
        assertTrue(opt.isPresent());
        assertEquals(OperatorScope.PLATFORM, opt.get().scope());
        assertFalse(opt.get().destructive());
    }

    @Test
    void testUnknownPermissionDenied() {
        var ctx = new OperatorContext("t-1", "u-1", "c-1", "r-1", Map.of());
        var req = OperatorAuthorizationRequest.tenant(ctx, "operator.unknown.action", "res", "1");

        var result = authorization.authorize(req);
        assertFalse(result.allowed());
        assertEquals("OPERATOR_PERMISSION_UNKNOWN", result.code());

        assertThrows(OperatorAuthorizationException.class, () -> authorization.require(req));
    }

    @Test
    void testScopeMismatchDenied() {
        var ctx = new OperatorContext("t-1", "u-1", "c-1", "r-1", Map.of());
        // operator.plugins.read is PLATFORM scope; requesting it with TENANT scope should fail
        var req = OperatorAuthorizationRequest.tenant(ctx, "operator.plugins.read", "plugin", "p-1");

        var result = authorization.authorize(req);
        assertFalse(result.allowed());
        assertEquals("OPERATOR_SCOPE_MISMATCH", result.code());
    }

    @Test
    void testTenantScopedRequiresTenantContext() {
        var ctxNoTenant = new OperatorContext(null, "u-1", "c-1", "r-1", Map.of());
        var req = OperatorAuthorizationRequest.tenant(ctxNoTenant, "operator.sandboxes.read", "sandbox", "sb-1");

        var result = authorization.authorize(req);
        assertFalse(result.allowed());
        assertEquals("OPERATOR_TENANT_REQUIRED", result.code());
    }


    @Test
    void testAnonymousContextReceivesNoImplicitRolesOrPermissions() {
        var context = new OperatorContext(null, null, "c-1", "r-1", Map.of());

        assertEquals(Set.of(), subjectResolver.roles(context));
        assertEquals(Set.of(), subjectResolver.permissions(context));
    }

    @Test
    void testTrustedContextClaimsAreUsedWithoutWildcardExpansion() {
        var context = new OperatorContext(
                "tenant-a",
                "user-a",
                "c-1",
                "r-1",
                Map.of(
                        "roles", Set.of("tenant-operator"),
                        "permissions", Set.of("operator.executions.read", "*")
                )
        );

        assertEquals(Set.of("tenant-operator"), subjectResolver.roles(context));
        assertEquals(Set.of("operator.executions.read"), subjectResolver.permissions(context));
    }

    @Test
    void testMissingPermissionIsDeniedEvenForAuthenticatedTenantUser() {
        var context = new OperatorContext("tenant-a", "user-a", "c-1", "r-1", Map.of());
        var request = OperatorAuthorizationRequest.tenant(
                context, "operator.executions.read", "execution", "exec-1");

        var result = authorization.authorize(request);

        assertFalse(result.allowed());
        assertEquals("OPERATOR_PERMISSION_DENIED", result.code());
    }

    @Test
    void testExplicitPermissionAndTenantContextAreRequired() {
        var context = new OperatorContext(
                "tenant-a",
                "user-a",
                "c-1",
                "r-1",
                Map.of("permissions", Set.of("operator.executions.read"))
        );
        var request = OperatorAuthorizationRequest.tenant(
                context, "operator.executions.read", "execution", "exec-1");

        assertTrue(authorization.authorize(request).allowed());
    }

    @Test
    void testWildcardGrantCannotBeConfigured() {
        assertThrows(IllegalArgumentException.class, () -> subjectResolver.grantPermission("*"));
        assertThrows(IllegalArgumentException.class, () -> subjectResolver.grantRole("*"));
    }

    @Test
    void testAuditRedactionAndIsolation() {
        var ctx = new OperatorContext("t-1", "u-1", "c-1", "r-1", Map.of());
        auditService.record(
                ctx,
                "operator.configuration.update",
                "operator.configuration.update",
                OperatorScope.TENANT,
                "configuration",
                "cfg-1",
                true,
                true,
                OperatorAuditOutcome.SUCCESS,
                "AUTHORIZED",
                "Updated",
                Map.of("password", "secret123", "normal", "visible"),
                Map.of("api_key", "key456"),
                Map.of("header", "val")
        );

        var events = auditService.getRecentEvents();
        assertEquals(1, events.size());
        var event = events.get(0);

        assertEquals("[REDACTED]", event.before().get("password"));
        assertEquals("visible", event.before().get("normal"));
        assertEquals("[REDACTED]", event.after().get("api_key"));
    }
}
