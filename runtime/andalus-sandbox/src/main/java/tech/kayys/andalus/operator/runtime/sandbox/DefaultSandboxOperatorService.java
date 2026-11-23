package tech.kayys.andalus.operator.runtime.sandbox;

import tech.kayys.andalus.operator.runtime.DenyAllOperatorAuthorization;
import tech.kayys.andalus.spi.operator.OperatorAuthorization;
import tech.kayys.andalus.spi.operator.OperatorAuthorizationException;
import tech.kayys.andalus.spi.operator.OperatorContext;
import tech.kayys.andalus.spi.operator.OperatorResult;
import tech.kayys.andalus.spi.operator.OperatorService;
import tech.kayys.andalus.spi.operator.sandbox.*;
import tech.kayys.andalus.spi.sandbox.Sandbox;
import tech.kayys.andalus.spi.sandbox.SandboxManager;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class DefaultSandboxOperatorService implements SandboxOperatorService, OperatorService {

    public static final String ID = "operator.service.sandbox";

    private final SandboxManager sandboxManager;
    private final OperatorAuthorization authorization;

    public DefaultSandboxOperatorService(SandboxManager sandboxManager) {
        this(sandboxManager, new DenyAllOperatorAuthorization());
    }

    public DefaultSandboxOperatorService(SandboxManager sandboxManager, OperatorAuthorization authorization) {
        this.sandboxManager = Objects.requireNonNull(sandboxManager, "sandboxManager must not be null");
        this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "Sandbox Operator Service";
    }

    @Override
    public String description() {
        return "Operator control plane for managing and inspecting Andalus execution sandboxes";
    }

    @Override
    public OperatorResult<List<SandboxSummary>> list(OperatorContext context) {
        try {
            authorization.require(context, SandboxOperatorPermissions.READ);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        try {
            List<Sandbox> sandboxes = sandboxManager.list();
            sandboxes = sandboxes.stream().filter(s -> belongsToTenant(context, s)).toList();
            List<SandboxSummary> summaries = sandboxes.stream()
                    .map(SandboxSummaryMapper::map)
                    .toList();
            return OperatorResult.success(summaries);
        } catch (Exception e) {
            return OperatorResult.failure("SANDBOX_LIST_FAILED", e.getMessage());
        }
    }

    @Override
    public OperatorResult<SandboxSummary> inspect(OperatorContext context, String sandboxId) {
        try {
            authorization.require(context, SandboxOperatorPermissions.READ);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        if (sandboxId == null || sandboxId.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "sandboxId must not be blank");
        }
        Optional<Sandbox> sb = sandboxManager.find(sandboxId);
        if (sb.isEmpty()) {
            return OperatorResult.failure("SANDBOX_NOT_FOUND", "No sandbox found with ID: " + sandboxId);
        }
        Sandbox s = sb.get();
        if (!belongsToTenant(context, s)) return notFound(sandboxId);
        return OperatorResult.success(SandboxSummaryMapper.map(s));
    }

    @Override
    public OperatorResult<SandboxHealthSummary> health(OperatorContext context, String sandboxId) {
        try {
            authorization.require(context, SandboxOperatorPermissions.HEALTH);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        if (sandboxId == null || sandboxId.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "sandboxId must not be blank");
        }
        Optional<Sandbox> sb = sandboxManager.find(sandboxId);
        if (sb.isEmpty()) {
            return OperatorResult.failure("SANDBOX_NOT_FOUND", "No sandbox found with ID: " + sandboxId);
        }
        if (!belongsToTenant(context, sb.get())) return notFound(sandboxId);\n        return OperatorResult.success(SandboxSummaryMapper.mapHealth(sb.get()));
    }

    @Override
    public OperatorResult<SandboxMetricsSummary> metrics(OperatorContext context, String sandboxId) {
        try {
            authorization.require(context, SandboxOperatorPermissions.METRICS);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        if (sandboxId == null || sandboxId.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "sandboxId must not be blank");
        }
        Optional<Sandbox> sb = sandboxManager.find(sandboxId);
        if (sb.isEmpty()) {
            return OperatorResult.failure("SANDBOX_NOT_FOUND", "No sandbox found with ID: " + sandboxId);
        }
        if (!belongsToTenant(context, sb.get())) return notFound(sandboxId);\n        return OperatorResult.success(SandboxSummaryMapper.mapMetrics(sb.get()));
    }

    @Override
    public OperatorResult<SandboxDiagnosticsSummary> diagnostics(OperatorContext context, String sandboxId) {
        try {
            authorization.require(context, SandboxOperatorPermissions.DIAGNOSTICS);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        if (sandboxId == null || sandboxId.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "sandboxId must not be blank");
        }
        Optional<Sandbox> sb = sandboxManager.find(sandboxId);
        if (sb.isEmpty()) {
            return OperatorResult.failure("SANDBOX_NOT_FOUND", "No sandbox found with ID: " + sandboxId);
        }
        if (!belongsToTenant(context, sb.get())) return notFound(sandboxId);\n        return OperatorResult.success(SandboxSummaryMapper.mapDiagnostics(sb.get()));
    }

    @Override
    public OperatorResult<Void> stop(OperatorContext context, String sandboxId) {
        try {
            authorization.require(context, SandboxOperatorPermissions.STOP);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        if (sandboxId == null || sandboxId.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "sandboxId must not be blank");
        }
        try {
            Sandbox sandbox = sandboxManager.find(sandboxId).orElse(null);\n            if (sandbox == null || !belongsToTenant(context, sandbox)) return notFound(sandboxId);\n            sandboxManager.stop(sandboxId);
            return OperatorResult.success(null);
        } catch (Exception e) {
            return OperatorResult.failure("SANDBOX_STOP_FAILED", e.getMessage());
        }
    }

    @Override
    public OperatorResult<Void> destroy(OperatorContext context, String sandboxId) {
        try {
            authorization.require(context, SandboxOperatorPermissions.DESTROY);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        if (sandboxId == null || sandboxId.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "sandboxId must not be blank");
        }
        try {
            Sandbox sandbox = sandboxManager.find(sandboxId).orElse(null);\n            if (sandbox == null || !belongsToTenant(context, sandbox)) return notFound(sandboxId);\n            sandboxManager.destroy(sandboxId);
            return OperatorResult.success(null);
        } catch (Exception e) {
            return OperatorResult.failure("SANDBOX_DESTROY_FAILED", e.getMessage());
        }
    }
}
