package tech.kayys.andalus.api.grpc.operator;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.operator.v1.*;
import tech.kayys.andalus.spi.diagnostics.DiagnosticComponent;
import tech.kayys.andalus.spi.diagnostics.DiagnosticComponentType;
import tech.kayys.andalus.spi.diagnostics.DiagnosticReport;
import tech.kayys.andalus.spi.diagnostics.DiagnosticResult;
import tech.kayys.andalus.spi.diagnostics.DiagnosticStatus;
import tech.kayys.andalus.spi.execution.ExecutionInfo;
import tech.kayys.andalus.spi.execution.ExecutionState;
import tech.kayys.andalus.spi.operator.OperatorResult;
import tech.kayys.andalus.spi.operator.configuration.ConfigurationOperatorService;
import tech.kayys.andalus.spi.operator.configuration.ConfigurationSummary;
import tech.kayys.andalus.spi.operator.diagnostics.DiagnosticsOperatorService;
import tech.kayys.andalus.spi.operator.execution.ExecutionOperatorService;
import tech.kayys.andalus.spi.operator.plugin.PluginOperatorService;
import tech.kayys.andalus.spi.operator.plugin.PluginSummary;
import tech.kayys.andalus.spi.operator.sandbox.SandboxHealthSummary;
import tech.kayys.andalus.spi.operator.sandbox.SandboxOperatorService;
import tech.kayys.andalus.spi.operator.sandbox.SandboxSummary;
import tech.kayys.andalus.spi.operator.tool.CapabilitySummary;
import tech.kayys.andalus.spi.operator.tool.ToolCapabilityOperatorService;
import tech.kayys.andalus.spi.operator.tool.ToolSummary;
import tech.kayys.andalus.spi.plugin.PluginState;
import tech.kayys.andalus.spi.sandbox.SandboxState;
import tech.kayys.andalus.spi.sandbox.SandboxType;
import tech.kayys.andalus.spi.sandbox.observability.SandboxHealthStatus;
import tech.kayys.andalus.spi.capability.CapabilityType;
import tech.kayys.andalus.spi.operator.session.SessionOperatorService;
import tech.kayys.andalus.spi.session.SessionId;
import tech.kayys.andalus.spi.session.SessionInfo;
import tech.kayys.andalus.spi.session.SessionState;

import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OperatorGrpcServiceTest {

    private OperatorGrpcService service;
    private PluginOperatorService mockPluginService;
    private SandboxOperatorService mockSandboxService;
    private ToolCapabilityOperatorService mockToolService;
    private ExecutionOperatorService mockExecutionService;
    private SessionOperatorService mockSessionService;
    private DiagnosticsOperatorService mockDiagnosticsService;
    private ConfigurationOperatorService mockConfigurationService;

    @BeforeEach
    void setUp() {
        service = new OperatorGrpcService();

        mockPluginService = (PluginOperatorService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PluginOperatorService.class},
                (proxy, method, args) -> {
                    if ("list".equals(method.getName())) {
                        return OperatorResult.success(List.of(
                                new PluginSummary("plug-1", "Plugin One", "1.0.0", "Sample plugin", PluginState.ACTIVE, List.of())
                        ));
                    }
                    if ("inspect".equals(method.getName())) {
                        String id = (String) args[1];
                        if ("plug-1".equals(id)) {
                            return OperatorResult.success(
                                    new PluginSummary("plug-1", "Plugin One", "1.0.0", "Sample plugin", PluginState.ACTIVE, List.of())
                            );
                        }
                        return OperatorResult.failure("PLUGIN_NOT_FOUND", "Plugin " + id + " not found");
                    }
                    return OperatorResult.success(null);
                }
        );

        mockSandboxService = (SandboxOperatorService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{SandboxOperatorService.class},
                (proxy, method, args) -> {
                    if ("list".equals(method.getName())) {
                        return OperatorResult.success(List.of(
                                new SandboxSummary("sb-1", "exec-1", "tenant-alpha", "agent-1", SandboxType.CONTAINER, SandboxState.RUNNING, "docker", Instant.now(), Instant.now(), Map.of())
                        ));
                    }
                    if ("inspect".equals(method.getName())) {
                        return OperatorResult.success(
                                new SandboxSummary("sb-1", "exec-1", "tenant-alpha", "agent-1", SandboxType.CONTAINER, SandboxState.RUNNING, "docker", Instant.now(), Instant.now(), Map.of())
                        );
                    }
                    if ("health".equals(method.getName())) {
                        return OperatorResult.success(
                                new SandboxHealthSummary("sb-1", SandboxHealthStatus.HEALTHY, Instant.now(), "All healthy", Map.of())
                        );
                    }
                    return OperatorResult.success(null);
                }
        );

        mockToolService = (ToolCapabilityOperatorService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ToolCapabilityOperatorService.class},
                (proxy, method, args) -> {
                    if ("listTools".equals(method.getName())) {
                        return OperatorResult.success(List.of(
                                new ToolSummary("bash-tool", "Runs bash scripts", "1.0.0", "builtin", List.of(), List.of("SHELL"), Map.of())
                        ));
                    }
                    if ("listCapabilities".equals(method.getName())) {
                        return OperatorResult.success(List.of(
                                new CapabilitySummary("cap-shell", CapabilityType.of("tool", "shell"), "Shell", "Shell capability", "1.0", "builtin", true, true, List.of("shell", "cli"), Map.of())
                        ));
                    }
                    return OperatorResult.success(null);
                }
        );

        mockExecutionService = (ExecutionOperatorService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ExecutionOperatorService.class},
                (proxy, method, args) -> {
                    if ("list".equals(method.getName())) {
                        return OperatorResult.success(List.of(
                                new ExecutionInfo("exec-100", "tenant-alpha", "user-1", "agent-1", "wf-1", ExecutionState.RUNNING, Instant.now(), Instant.now(), null, Instant.now(), "corr-1", null, null, Map.of())
                        ));
                    }
                    if ("inspect".equals(method.getName())) {
                        return OperatorResult.success(
                                new ExecutionInfo("exec-100", "tenant-alpha", "user-1", "agent-1", "wf-1", ExecutionState.RUNNING, Instant.now(), Instant.now(), null, Instant.now(), "corr-1", null, null, Map.of())
                        );
                    }
                    return OperatorResult.success(null);
                }
        );

        mockSessionService = (SessionOperatorService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{SessionOperatorService.class},
                (proxy, method, args) -> {
                    if ("list".equals(method.getName())) {
                        return OperatorResult.success(List.of(
                                new SessionInfo(SessionId.of("sess-200"), "tenant-alpha", "user-1", "agent-1", SessionState.ACTIVE, Instant.now(), Instant.now(), null, "corr-1", List.of(), Map.of())
                        ));
                    }
                    if ("inspect".equals(method.getName())) {
                        return OperatorResult.success(
                                new SessionInfo(SessionId.of("sess-200"), "tenant-alpha", "user-1", "agent-1", SessionState.ACTIVE, Instant.now(), Instant.now(), null, "corr-1", List.of(), Map.of())
                        );
                    }
                    return OperatorResult.success(null);
                }
        );

        mockDiagnosticsService = (DiagnosticsOperatorService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{DiagnosticsOperatorService.class},
                (proxy, method, args) -> {
                    if ("diagnoseAll".equals(method.getName())) {
                        return OperatorResult.success(new DiagnosticReport(
                                Instant.now(), DiagnosticStatus.HEALTHY,
                                List.of(DiagnosticResult.healthy(DiagnosticComponent.of(DiagnosticComponentType.SANDBOX, "sb-1"), "Sandbox healthy")),
                                Map.of()
                        ));
                    }
                    return OperatorResult.success(null);
                }
        );

        mockConfigurationService = (ConfigurationOperatorService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ConfigurationOperatorService.class},
                (proxy, method, args) -> {
                    if ("active".equals(method.getName())) {
                        return OperatorResult.success(new ConfigurationSummary(
                                "cfg-1", "production-config", "PRODUCTION", "APPLICATION", null, "ACTIVE",
                                Map.of("timeout", "30s"), Instant.now()
                        ));
                    }
                    return OperatorResult.success(null);
                }
        );

        service.pluginService = new SimpleInstance<>(mockPluginService);
        service.sandboxService = new SimpleInstance<>(mockSandboxService);
        service.toolCapabilityService = new SimpleInstance<>(mockToolService);
        service.executionService = new SimpleInstance<>(mockExecutionService);
        service.sessionService = new SimpleInstance<>(mockSessionService);
        service.diagnosticsService = new SimpleInstance<>(mockDiagnosticsService);
        service.configurationService = new SimpleInstance<>(mockConfigurationService);
    }

    @Test
    void testListPlugins() {
        ListPluginsRequest req = ListPluginsRequest.newBuilder()
                .setContext(OperatorRequestContext.newBuilder().putAttributes("tenantId", "tenant-alpha").build())
                .build();

        ListPluginsResponse response = service.listPlugins(req).await().indefinitely();
        assertNotNull(response);
        assertEquals(1, response.getPluginsCount());
        assertEquals("plug-1", response.getPlugins(0).getId());
        assertEquals("Plugin One", response.getPlugins(0).getName());
        assertEquals("ACTIVE", response.getPlugins(0).getState());
    }

    @Test
    void testGetPluginSuccessAndNotFound() {
        GetPluginRequest reqSuccess = GetPluginRequest.newBuilder()
                .setPluginId("plug-1")
                .setContext(OperatorRequestContext.newBuilder().putAttributes("tenantId", "tenant-alpha").build())
                .build();

        GetPluginResponse response = service.getPlugin(reqSuccess).await().indefinitely();
        assertNotNull(response);
        assertEquals("plug-1", response.getPlugin().getId());

        GetPluginRequest reqNotFound = GetPluginRequest.newBuilder()
                .setPluginId("non-existent")
                .setContext(OperatorRequestContext.newBuilder().putAttributes("tenantId", "tenant-alpha").build())
                .build();

        StatusRuntimeException thrown = assertThrows(StatusRuntimeException.class, () ->
                service.getPlugin(reqNotFound).await().indefinitely()
        );
        assertEquals(Status.Code.NOT_FOUND, thrown.getStatus().getCode());
    }

    @Test
    void testListSandboxes() {
        ListSandboxesRequest req = ListSandboxesRequest.newBuilder()
                .setContext(OperatorRequestContext.newBuilder().putAttributes("tenantId", "tenant-alpha").build())
                .build();

        ListSandboxesResponse response = service.listSandboxes(req).await().indefinitely();
        assertNotNull(response);
        assertEquals(1, response.getSandboxesCount());
        assertEquals("sb-1", response.getSandboxes(0).getId());
        assertEquals("tenant-alpha", response.getSandboxes(0).getTenantId());
        assertEquals("RUNNING", response.getSandboxes(0).getState());
    }

    @Test
    void testGetSandboxHealth() {
        SandboxRequest req = SandboxRequest.newBuilder()
                .setSandboxId("sb-1")
                .build();

        SandboxHealth response = service.getSandboxHealth(req).await().indefinitely();
        assertNotNull(response);
        assertEquals("sb-1", response.getSandboxId());
        assertEquals("HEALTHY", response.getStatus());
        assertEquals("All healthy", response.getSummary());
    }

    @Test
    void testListToolsAndCapabilities() {
        ListToolsResponse tools = service.listTools(ListToolsRequest.getDefaultInstance()).await().indefinitely();
        assertNotNull(tools);
        assertEquals(1, tools.getToolsCount());
        assertEquals("bash-tool", tools.getTools(0).getName());

        ListCapabilitiesResponse caps = service.listCapabilities(ListCapabilitiesRequest.getDefaultInstance()).await().indefinitely();
        assertNotNull(caps);
        assertEquals(1, caps.getCapabilitiesCount());
        assertEquals("cap-shell", caps.getCapabilities(0).getId());
    }

    @Test
    void testListAndGetExecutions() {
        ListExecutionsResponse execs = service.listExecutions(ListExecutionsRequest.getDefaultInstance()).await().indefinitely();
        assertNotNull(execs);
        assertEquals(1, execs.getExecutionsCount());
        assertEquals("exec-100", execs.getExecutions(0).getExecutionId());

        tech.kayys.andalus.operator.v1.ExecutionSummary exec = service.getExecution(ExecutionRequest.newBuilder().setExecutionId("exec-100").build()).await().indefinitely();
        assertNotNull(exec);
        assertEquals("exec-100", exec.getExecutionId());
        assertEquals("RUNNING", exec.getState());
    }

    @Test
    void testListAndGetSessions() {
        ListSessionsResponse sessions = service.listSessions(ListSessionsRequest.getDefaultInstance()).await().indefinitely();
        assertNotNull(sessions);
        assertEquals(1, sessions.getSessionsCount());
        assertEquals("sess-200", sessions.getSessions(0).getSessionId());

        tech.kayys.andalus.operator.v1.SessionSummary session = service.getSession(SessionRequest.newBuilder().setSessionId("sess-200").build()).await().indefinitely();
        assertNotNull(session);
        assertEquals("sess-200", session.getSessionId());
        assertEquals("ACTIVE", session.getState());
    }

    @Test
    void testDiagnoseAll() {
        tech.kayys.andalus.operator.v1.DiagnosticReport report = service.diagnoseAll(DiagnoseAllRequest.getDefaultInstance()).await().indefinitely();
        assertNotNull(report);
        assertEquals("HEALTHY", report.getOverallStatus());
        assertEquals(1, report.getResultsCount());
        assertEquals("SANDBOX", report.getResults(0).getComponentType());
    }

    @Test
    void testGetActiveConfiguration() {
        OperatorRequestContext req = OperatorRequestContext.newBuilder()
                .putAttributes("tenantId", "tenant-alpha")
                .build();

        tech.kayys.andalus.operator.v1.ConfigurationSummary response = service.getActiveConfiguration(req).await().indefinitely();
        assertNotNull(response);
        assertEquals("cfg-1", response.getId());
        assertEquals("ACTIVE", response.getStatus());
        assertTrue(response.getActive());
    }

    @Test
    void testOperatorGrpcErrorMapper() {
        StatusRuntimeException ex1 = OperatorGrpcErrorMapper.toStatusException(
                OperatorResult.failure("OPERATOR_PERMISSION_DENIED", "Access denied to resource")
        );
        assertEquals(Status.Code.PERMISSION_DENIED, ex1.getStatus().getCode());

        StatusRuntimeException ex2 = OperatorGrpcErrorMapper.toStatusException(
                OperatorResult.failure("OPERATOR_INVALID_REQUEST", "Malformed param")
        );
        assertEquals(Status.Code.INVALID_ARGUMENT, ex2.getStatus().getCode());

        StatusRuntimeException ex3 = OperatorGrpcErrorMapper.toStatusException(
                OperatorResult.failure("SERVICE_UNAVAILABLE", "Down")
        );
        assertEquals(Status.Code.UNAVAILABLE, ex3.getStatus().getCode());
    }

    @Test
    void testOperatorGrpcContextResolver() {
        OperatorGrpcContextResolver resolver = new OperatorGrpcContextResolver();
        OperatorRequestContext protoCtx = OperatorRequestContext.newBuilder()
                .setCorrelationId("c-123")
                .setRequestId("r-456")
                .putAttributes("tenantId", "tenant-xyz")
                .putAttributes("userId", "user-abc")
                .putAttributes("roles", "admin")
                .putAttributes("principalId", "admin")
                .putAttributes("principalType", "USER")
                .putAttributes("permissions", "operator.plugins.enable")
                .build();

        var ctx = resolver.resolve(protoCtx);
        assertEquals("c-123", ctx.correlationId());
        assertEquals("r-456", ctx.requestId());
        assertNull(ctx.tenantId(), "protobuf attributes must not establish tenant identity");
        assertNull(ctx.userId(), "protobuf attributes must not establish user identity");
        assertEquals(Boolean.FALSE, ctx.attributes().get("authenticated"));
        assertEquals(java.util.Set.of(), ctx.attributes().get("roles"));
        assertFalse(ctx.attributes().containsKey("tenantId"));
        assertFalse(ctx.attributes().containsKey("userId"));
        assertFalse(ctx.attributes().containsKey("principalId"));
        assertFalse(ctx.attributes().containsKey("principalType"));
        assertEquals(java.util.Set.of(), ctx.attributes().get("permissions"),
                "protobuf attributes must not establish operator permissions");
    }

    @Test
    void trustedSecurityIdentityOverridesForgedGrpcClaims() {
        io.quarkus.security.identity.SecurityIdentity identity =
                (io.quarkus.security.identity.SecurityIdentity) Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{io.quarkus.security.identity.SecurityIdentity.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "isAnonymous" -> false;
                            case "getPrincipal" -> (java.security.Principal) () -> "verified-user";
                            case "getRoles" -> java.util.Set.of("operator");
                            case "getAttribute" -> switch ((String) args[0]) {
                                case "tenantId" -> "verified-tenant";
                                case "permissions" -> java.util.Set.of("operator.plugins.read");
                                default -> null;
                            };
                            default -> null;
                        });

        OperatorRequestContext protoCtx = OperatorRequestContext.newBuilder()
                .putAttributes("tenantId", "forged-tenant")
                .putAttributes("userId", "forged-user")
                .putAttributes("roles", "admin")
                .build();

        var context = new OperatorGrpcContextResolver().resolve(protoCtx, identity);

        assertEquals("verified-tenant", context.tenantId());
        assertEquals("verified-user", context.userId());
        assertEquals(java.util.Set.of("operator"), context.attributes().get("roles"));
        assertEquals(java.util.Set.of("operator.plugins.read"), context.attributes().get("permissions"));
        assertEquals(Boolean.TRUE, context.attributes().get("authenticated"));
    }

    // Helper mock Instance implementation
    static class SimpleInstance<T> implements Instance<T> {
        private final T value;

        SimpleInstance(T value) {
            this.value = value;
        }

        @Override public Instance<T> select(Annotation... qualifiers) { return this; }
        @Override public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) { return null; }
        @Override public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) { return null; }
        @Override public boolean isUnsatisfied() { return value == null; }
        @Override public boolean isAmbiguous() { return false; }
        @Override public void destroy(T instance) {}
        @Override public Handle<T> getHandle() { return null; }
        @Override public Iterable<? extends Handle<T>> handles() { return List.of(); }
        @Override public T get() { return value; }
        @Override public boolean isResolvable() { return value != null; }
        @Override public Iterator<T> iterator() { return List.of(value).iterator(); }
    }

    static class EmptyInstance<T> implements Instance<T> {
        @Override public Instance<T> select(Annotation... qualifiers) { return this; }
        @Override public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) { return null; }
        @Override public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) { return null; }
        @Override public boolean isUnsatisfied() { return true; }
        @Override public boolean isAmbiguous() { return false; }
        @Override public void destroy(T instance) {}
        @Override public Handle<T> getHandle() { return null; }
        @Override public Iterable<? extends Handle<T>> handles() { return List.of(); }
        @Override public T get() { return null; }
        @Override public boolean isResolvable() { return false; }
        @Override public Iterator<T> iterator() { return List.<T>of().iterator(); }
    }
}
