package tech.kayys.andalus.api.grpc.operator;

import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import tech.kayys.andalus.api.grpc.operator.mapper.*;
import tech.kayys.andalus.operator.v1.*;
import tech.kayys.andalus.spi.operator.OperatorContext;
import tech.kayys.andalus.spi.operator.OperatorResult;
import tech.kayys.andalus.spi.operator.configuration.ConfigurationOperatorService;
import tech.kayys.andalus.spi.operator.configuration.ConfigurationUpdateRequest;
import tech.kayys.andalus.spi.operator.diagnostics.DiagnosticsOperatorService;
import tech.kayys.andalus.spi.operator.execution.ExecutionOperatorService;
import tech.kayys.andalus.spi.operator.plugin.PluginOperatorService;
import tech.kayys.andalus.spi.operator.sandbox.SandboxOperatorService;
import tech.kayys.andalus.spi.operator.session.SessionOperatorService;
import tech.kayys.andalus.spi.operator.tool.ToolCapabilityOperatorService;
import tech.kayys.andalus.spi.session.SessionId;
import tech.kayys.andalus.spi.session.SessionQuery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Singleton;

/**
 * Unified gRPC implementation of the Andalus Operator control-plane API.
 * Delegates directly to the operator SPI service layer.
 */
@GrpcService
@Singleton
public class OperatorGrpcService implements OperatorService {

    @Inject
    Instance<PluginOperatorService> pluginService;

    @Inject
    Instance<SandboxOperatorService> sandboxService;

    @Inject
    Instance<ToolCapabilityOperatorService> toolCapabilityService;

    @Inject
    Instance<ExecutionOperatorService> executionService;

    @Inject
    Instance<SessionOperatorService> sessionService;

    @Inject
    Instance<DiagnosticsOperatorService> diagnosticsService;

    @Inject
    Instance<ConfigurationOperatorService> configurationService;

    @Inject
    OperatorGrpcContextResolver contextResolver;

    @Inject
    SecurityIdentity securityIdentity;

    public OperatorGrpcService() {
        this.contextResolver = new OperatorGrpcContextResolver();
    }

    public OperatorGrpcService(OperatorGrpcContextResolver contextResolver) {
        this.contextResolver = contextResolver != null ? contextResolver : new OperatorGrpcContextResolver();
    }

    private OperatorContext toContext(OperatorRequestContext reqCtx) {
        return contextResolver != null
                ? contextResolver.resolve(reqCtx, securityIdentity)
                : new OperatorGrpcContextResolver().resolve(reqCtx, securityIdentity);
    }

    @SuppressWarnings("unchecked")
    private <T> Uni<T> handleResult(OperatorResult<?> result) {
        if (result instanceof OperatorResult.Success<?> s) {
            return Uni.createFrom().item((T) s.value());
        }
        if (result instanceof OperatorResult.Failure<?> f) {
            return Uni.createFrom().failure(OperatorGrpcErrorMapper.toStatusException(f));
        }
        return Uni.createFrom().failure(Status.INTERNAL.asRuntimeException());
    }

    // ─── Plugin Operations ──────────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public Uni<ListPluginsResponse> listPlugins(ListPluginsRequest request) {
        if (!pluginService.isResolvable()) {
            return Uni.createFrom().item(ListPluginsResponse.getDefaultInstance());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = pluginService.get().list(ctx);
        if (res instanceof OperatorResult.Success<?> s) {
            return Uni.createFrom().item(PluginGrpcMapper.toListResponse((List<tech.kayys.andalus.spi.operator.plugin.PluginSummary>) s.value()));
        }
        return handleResult(res);
    }

    @Override
    public Uni<GetPluginResponse> getPlugin(GetPluginRequest request) {
        if (!pluginService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.withDescription("Plugin service unavailable").asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = pluginService.get().inspect(ctx, request.getPluginId());
        if (res instanceof OperatorResult.Success<tech.kayys.andalus.spi.operator.plugin.PluginSummary> s) {
            return Uni.createFrom().item(PluginGrpcMapper.toGetResponse(s.value()));
        }
        return handleResult(res);
    }

    @Override
    public Uni<OperationResult> enablePlugin(PluginOperationRequest request) {
        if (!pluginService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = pluginService.get().enable(ctx, request.getPluginId());
        if (res instanceof OperatorResult.Success<?>) {
            return Uni.createFrom().item(OperationResult.newBuilder()
                    .setOperationId("enable")
                    .setResourceId(request.getPluginId())
                    .build());
        }
        return handleResult(res);
    }

    @Override
    public Uni<OperationResult> disablePlugin(PluginOperationRequest request) {
        if (!pluginService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = pluginService.get().disable(ctx, request.getPluginId());
        if (res instanceof OperatorResult.Success<?>) {
            return Uni.createFrom().item(OperationResult.newBuilder()
                    .setOperationId("disable")
                    .setResourceId(request.getPluginId())
                    .build());
        }
        return handleResult(res);
    }

    @Override
    public Uni<OperationResult> unloadPlugin(PluginOperationRequest request) {
        if (!pluginService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = pluginService.get().unload(ctx, request.getPluginId());
        if (res instanceof OperatorResult.Success<?>) {
            return Uni.createFrom().item(OperationResult.newBuilder()
                    .setOperationId("unload")
                    .setResourceId(request.getPluginId())
                    .build());
        }
        return handleResult(res);
    }

    // ─── Sandbox Operations ─────────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public Uni<ListSandboxesResponse> listSandboxes(ListSandboxesRequest request) {
        if (!sandboxService.isResolvable()) {
            return Uni.createFrom().item(ListSandboxesResponse.getDefaultInstance());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = sandboxService.get().list(ctx);
        if (res instanceof OperatorResult.Success<?> s) {
            return Uni.createFrom().item(SandboxGrpcMapper.toListResponse((List<tech.kayys.andalus.spi.operator.sandbox.SandboxSummary>) s.value()));
        }
        return handleResult(res);
    }

    @Override
    public Uni<SandboxSummary> getSandbox(SandboxRequest request) {
        if (!sandboxService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = sandboxService.get().inspect(ctx, request.getSandboxId());
        if (res instanceof OperatorResult.Success<tech.kayys.andalus.spi.operator.sandbox.SandboxSummary> s) {
            return Uni.createFrom().item(SandboxGrpcMapper.toProto(s.value()));
        }
        return handleResult(res);
    }

    @Override
    public Uni<SandboxHealth> getSandboxHealth(SandboxRequest request) {
        if (!sandboxService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = sandboxService.get().health(ctx, request.getSandboxId());
        if (res instanceof OperatorResult.Success<tech.kayys.andalus.spi.operator.sandbox.SandboxHealthSummary> s) {
            return Uni.createFrom().item(SandboxGrpcMapper.toHealthProto(s.value()));
        }
        return handleResult(res);
    }

    @Override
    public Uni<SandboxMetrics> getSandboxMetrics(SandboxRequest request) {
        if (!sandboxService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = sandboxService.get().metrics(ctx, request.getSandboxId());
        if (res instanceof OperatorResult.Success<tech.kayys.andalus.spi.operator.sandbox.SandboxMetricsSummary> s) {
            return Uni.createFrom().item(SandboxGrpcMapper.toMetricsProto(s.value()));
        }
        return handleResult(res);
    }

    @Override
    public Uni<SandboxDiagnostics> getSandboxDiagnostics(SandboxRequest request) {
        if (!sandboxService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = sandboxService.get().diagnostics(ctx, request.getSandboxId());
        if (res instanceof OperatorResult.Success<tech.kayys.andalus.spi.operator.sandbox.SandboxDiagnosticsSummary> s) {
            return Uni.createFrom().item(SandboxGrpcMapper.toDiagnosticsProto(s.value()));
        }
        return handleResult(res);
    }

    @Override
    public Uni<OperationResult> stopSandbox(SandboxRequest request) {
        if (!sandboxService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = sandboxService.get().stop(ctx, request.getSandboxId());
        if (res instanceof OperatorResult.Success<?>) {
            return Uni.createFrom().item(OperationResult.newBuilder()
                    .setOperationId("stop")
                    .setResourceId(request.getSandboxId())
                    .build());
        }
        return handleResult(res);
    }

    @Override
    public Uni<OperationResult> destroySandbox(SandboxRequest request) {
        if (!sandboxService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = sandboxService.get().destroy(ctx, request.getSandboxId());
        if (res instanceof OperatorResult.Success<?>) {
            return Uni.createFrom().item(OperationResult.newBuilder()
                    .setOperationId("destroy")
                    .setResourceId(request.getSandboxId())
                    .build());
        }
        return handleResult(res);
    }

    // ─── Tool and Capability Operations ──────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public Uni<ListToolsResponse> listTools(ListToolsRequest request) {
        if (!toolCapabilityService.isResolvable()) {
            return Uni.createFrom().item(ListToolsResponse.getDefaultInstance());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = toolCapabilityService.get().listTools(ctx);
        if (res instanceof OperatorResult.Success<?> s) {
            return Uni.createFrom().item(ToolGrpcMapper.toListResponse((List<tech.kayys.andalus.spi.operator.tool.ToolSummary>) s.value()));
        }
        return handleResult(res);
    }

    @Override
    public Uni<ToolSummary> getTool(ToolRequest request) {
        if (!toolCapabilityService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = toolCapabilityService.get().inspectTool(ctx, request.getToolId());
        if (res instanceof OperatorResult.Success<tech.kayys.andalus.spi.operator.tool.ToolSummary> s) {
            return Uni.createFrom().item(ToolGrpcMapper.toProto(s.value()));
        }
        return handleResult(res);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<ListCapabilitiesResponse> listCapabilities(ListCapabilitiesRequest request) {
        if (!toolCapabilityService.isResolvable()) {
            return Uni.createFrom().item(ListCapabilitiesResponse.getDefaultInstance());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = toolCapabilityService.get().listCapabilities(ctx);
        if (res instanceof OperatorResult.Success<?> s) {
            return Uni.createFrom().item(CapabilityGrpcMapper.toListResponse((List<tech.kayys.andalus.spi.operator.tool.CapabilitySummary>) s.value()));
        }
        return handleResult(res);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<ListCapabilitiesResponse> listCapabilitiesByType(ListCapabilitiesByTypeRequest request) {
        if (!toolCapabilityService.isResolvable()) {
            return Uni.createFrom().item(ListCapabilitiesResponse.getDefaultInstance());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = toolCapabilityService.get().findCapabilitiesByType(ctx, request.getType());
        if (res instanceof OperatorResult.Success<?> s) {
            return Uni.createFrom().item(CapabilityGrpcMapper.toListResponse((List<tech.kayys.andalus.spi.operator.tool.CapabilitySummary>) s.value()));
        }
        return handleResult(res);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<ListCapabilitiesResponse> listCapabilitiesByTag(ListCapabilitiesByTagRequest request) {
        if (!toolCapabilityService.isResolvable()) {
            return Uni.createFrom().item(ListCapabilitiesResponse.getDefaultInstance());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = toolCapabilityService.get().findCapabilitiesByTag(ctx, request.getTag());
        if (res instanceof OperatorResult.Success<?> s) {
            return Uni.createFrom().item(CapabilityGrpcMapper.toListResponse((List<tech.kayys.andalus.spi.operator.tool.CapabilitySummary>) s.value()));
        }
        return handleResult(res);
    }

    // ─── Execution Operations ───────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public Uni<ListExecutionsResponse> listExecutions(ListExecutionsRequest request) {
        if (!executionService.isResolvable()) {
            return Uni.createFrom().item(ListExecutionsResponse.getDefaultInstance());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = executionService.get().list(ctx, tech.kayys.andalus.spi.execution.ExecutionQuery.all());
        if (res instanceof OperatorResult.Success<?> s) {
            return Uni.createFrom().item(ExecutionGrpcMapper.toListResponse((List<tech.kayys.andalus.spi.execution.ExecutionInfo>) s.value()));
        }
        return handleResult(res);
    }

    @Override
    public Uni<ExecutionSummary> getExecution(ExecutionRequest request) {
        if (!executionService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = executionService.get().inspect(ctx, request.getExecutionId());
        if (res instanceof OperatorResult.Success<tech.kayys.andalus.spi.execution.ExecutionInfo> s) {
            return Uni.createFrom().item(ExecutionGrpcMapper.toProto(s.value()));
        }
        return handleResult(res);
    }

    @Override
    public Uni<OperationResult> pauseExecution(ExecutionRequest request) {
        if (!executionService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = executionService.get().pause(ctx, request.getExecutionId());
        if (res instanceof OperatorResult.Success<?>) {
            return Uni.createFrom().item(OperationResult.newBuilder()
                    .setOperationId("pause")
                    .setResourceId(request.getExecutionId())
                    .build());
        }
        return handleResult(res);
    }

    @Override
    public Uni<OperationResult> resumeExecution(ExecutionRequest request) {
        if (!executionService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = executionService.get().resume(ctx, request.getExecutionId());
        if (res instanceof OperatorResult.Success<?>) {
            return Uni.createFrom().item(OperationResult.newBuilder()
                    .setOperationId("resume")
                    .setResourceId(request.getExecutionId())
                    .build());
        }
        return handleResult(res);
    }

    @Override
    public Uni<OperationResult> cancelExecution(ExecutionRequest request) {
        if (!executionService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = executionService.get().cancel(ctx, request.getExecutionId());
        if (res instanceof OperatorResult.Success<?>) {
            return Uni.createFrom().item(OperationResult.newBuilder()
                    .setOperationId("cancel")
                    .setResourceId(request.getExecutionId())
                    .build());
        }
        return handleResult(res);
    }

    @Override
    public Uni<OperationResult> retryExecution(ExecutionRequest request) {
        if (!executionService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = executionService.get().retry(ctx, request.getExecutionId());
        if (res instanceof OperatorResult.Success<?>) {
            return Uni.createFrom().item(OperationResult.newBuilder()
                    .setOperationId("retry")
                    .setResourceId(request.getExecutionId())
                    .build());
        }
        return handleResult(res);
    }

    // ─── Session Operations ─────────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public Uni<ListSessionsResponse> listSessions(ListSessionsRequest request) {
        if (!sessionService.isResolvable()) {
            return Uni.createFrom().item(ListSessionsResponse.getDefaultInstance());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = sessionService.get().list(ctx, SessionQuery.all());
        if (res instanceof OperatorResult.Success<?> s) {
            return Uni.createFrom().item(SessionGrpcMapper.toListResponse((List<tech.kayys.andalus.spi.session.SessionInfo>) s.value()));
        }
        return handleResult(res);
    }

    @Override
    public Uni<SessionSummary> getSession(SessionRequest request) {
        if (!sessionService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = sessionService.get().inspect(ctx, SessionId.of(request.getSessionId()));
        if (res instanceof OperatorResult.Success<tech.kayys.andalus.spi.session.SessionInfo> s) {
            return Uni.createFrom().item(SessionGrpcMapper.toProto(s.value()));
        }
        return handleResult(res);
    }

    @Override
    public Uni<OperationResult> suspendSession(SessionRequest request) {
        if (!sessionService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = sessionService.get().suspend(ctx, SessionId.of(request.getSessionId()));
        if (res instanceof OperatorResult.Success<?>) {
            return Uni.createFrom().item(OperationResult.newBuilder()
                    .setOperationId("suspend")
                    .setResourceId(request.getSessionId())
                    .build());
        }
        return handleResult(res);
    }

    @Override
    public Uni<OperationResult> resumeSession(SessionRequest request) {
        if (!sessionService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = sessionService.get().resume(ctx, SessionId.of(request.getSessionId()));
        if (res instanceof OperatorResult.Success<?>) {
            return Uni.createFrom().item(OperationResult.newBuilder()
                    .setOperationId("resume")
                    .setResourceId(request.getSessionId())
                    .build());
        }
        return handleResult(res);
    }

    @Override
    public Uni<OperationResult> closeSession(SessionRequest request) {
        if (!sessionService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = sessionService.get().close(ctx, SessionId.of(request.getSessionId()));
        if (res instanceof OperatorResult.Success<?>) {
            return Uni.createFrom().item(OperationResult.newBuilder()
                    .setOperationId("close")
                    .setResourceId(request.getSessionId())
                    .build());
        }
        return handleResult(res);
    }

    // ─── Diagnostics Operations ─────────────────────────────────────────────

    @Override
    public Uni<DiagnosticReport> diagnoseAll(DiagnoseAllRequest request) {
        if (!diagnosticsService.isResolvable()) {
            return Uni.createFrom().item(DiagnosticReport.getDefaultInstance());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = diagnosticsService.get().diagnoseAll(ctx);
        if (res instanceof OperatorResult.Success<tech.kayys.andalus.spi.diagnostics.DiagnosticReport> s) {
            return Uni.createFrom().item(DiagnosticsGrpcMapper.toReportProto(s.value()));
        }
        return handleResult(res);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<DiagnosticReport> diagnoseType(DiagnoseTypeRequest request) {
        if (!diagnosticsService.isResolvable()) {
            return Uni.createFrom().item(DiagnosticReport.getDefaultInstance());
        }
        OperatorContext ctx = toContext(request.getContext());
        tech.kayys.andalus.spi.diagnostics.DiagnosticComponentType type;
        try {
            type = tech.kayys.andalus.spi.diagnostics.DiagnosticComponentType.valueOf(request.getType().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("Invalid diagnostic component type: " + request.getType()).asRuntimeException());
        }
        var res = diagnosticsService.get().diagnoseType(ctx, type);
        if (res instanceof OperatorResult.Success<?> s) {
            return Uni.createFrom().item(DiagnosticsGrpcMapper.toReportProto((List<tech.kayys.andalus.spi.diagnostics.DiagnosticResult>) s.value()));
        }
        return handleResult(res);
    }

    @Override
    public Uni<DiagnosticResult> diagnoseComponent(DiagnoseComponentRequest request) {
        if (!diagnosticsService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        tech.kayys.andalus.spi.diagnostics.DiagnosticComponentType type;
        try {
            type = tech.kayys.andalus.spi.diagnostics.DiagnosticComponentType.valueOf(request.getType().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("Invalid diagnostic component type: " + request.getType()).asRuntimeException());
        }
        var component = tech.kayys.andalus.spi.diagnostics.DiagnosticComponent.of(type, request.getComponentId());
        var res = diagnosticsService.get().diagnose(ctx, component);
        if (res instanceof OperatorResult.Success<tech.kayys.andalus.spi.diagnostics.DiagnosticResult> s) {
            return Uni.createFrom().item(DiagnosticsGrpcMapper.toProto(s.value()));
        }
        return handleResult(res);
    }

    // ─── Configuration Operations ───────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public Uni<ListConfigurationsResponse> listConfigurations(ListConfigurationsRequest request) {
        if (!configurationService.isResolvable()) {
            return Uni.createFrom().item(ListConfigurationsResponse.getDefaultInstance());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = configurationService.get().list(ctx);
        if (res instanceof OperatorResult.Success<?> s) {
            return Uni.createFrom().item(ConfigurationGrpcMapper.toListResponse((List<tech.kayys.andalus.spi.operator.configuration.ConfigurationSummary>) s.value()));
        }
        return handleResult(res);
    }

    @Override
    public Uni<ConfigurationSummary> getConfiguration(ConfigurationRequest request) {
        if (!configurationService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = configurationService.get().inspect(ctx, request.getConfigurationId());
        if (res instanceof OperatorResult.Success<tech.kayys.andalus.spi.operator.configuration.ConfigurationSummary> s) {
            return Uni.createFrom().item(ConfigurationGrpcMapper.toProto(s.value()));
        }
        return handleResult(res);
    }

    @Override
    public Uni<ConfigurationSummary> getActiveConfiguration(OperatorRequestContext request) {
        if (!configurationService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request);
        var res = configurationService.get().active(ctx);
        if (res instanceof OperatorResult.Success<tech.kayys.andalus.spi.operator.configuration.ConfigurationSummary> s) {
            return Uni.createFrom().item(ConfigurationGrpcMapper.toProto(s.value()));
        }
        return handleResult(res);
    }

    @Override
    public Uni<ConfigurationSummary> updateConfiguration(UpdateConfigurationRequest request) {
        if (!configurationService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        Map<String, Object> values = new HashMap<>(request.getValuesMap());
        var req = new ConfigurationUpdateRequest(values, request.getActivate());
        var res = configurationService.get().update(ctx, request.getConfigurationId(), req);
        if (res instanceof OperatorResult.Success<tech.kayys.andalus.spi.operator.configuration.ConfigurationSummary> s) {
            return Uni.createFrom().item(ConfigurationGrpcMapper.toProto(s.value()));
        }
        return handleResult(res);
    }

    @Override
    public Uni<OperationResult> activateConfiguration(ConfigurationRequest request) {
        if (!configurationService.isResolvable()) {
            return Uni.createFrom().failure(Status.UNAVAILABLE.asRuntimeException());
        }
        OperatorContext ctx = toContext(request.getContext());
        var res = configurationService.get().activate(ctx, request.getConfigurationId());
        if (res instanceof OperatorResult.Success<?>) {
            return Uni.createFrom().item(OperationResult.newBuilder()
                    .setOperationId("activate")
                    .setResourceId(request.getConfigurationId())
                    .build());
        }
        return handleResult(res);
    }
}
