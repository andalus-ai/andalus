package tech.kayys.andalus.operator.runtime.execution;

import tech.kayys.andalus.operator.runtime.DenyAllOperatorAuthorization;
import tech.kayys.andalus.spi.execution.ExecutionControl;
import tech.kayys.andalus.spi.execution.ExecutionInfo;
import tech.kayys.andalus.spi.execution.ExecutionQuery;
import tech.kayys.andalus.spi.operator.OperatorAuthorization;
import tech.kayys.andalus.spi.operator.OperatorAuthorizationException;
import tech.kayys.andalus.spi.operator.OperatorContext;
import tech.kayys.andalus.spi.operator.OperatorResult;
import tech.kayys.andalus.spi.operator.OperatorService;
import tech.kayys.andalus.spi.operator.execution.ExecutionOperatorPermissions;
import tech.kayys.andalus.spi.operator.execution.ExecutionOperatorService;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class DefaultExecutionOperatorService implements ExecutionOperatorService, OperatorService {

    public static final String ID = "operator.service.execution";

    private final ExecutionControl executionControl;
    private final OperatorAuthorization authorization;

    public DefaultExecutionOperatorService(ExecutionControl executionControl) {
        this(executionControl, new DenyAllOperatorAuthorization());
    }

    public DefaultExecutionOperatorService(
            ExecutionControl executionControl,
            OperatorAuthorization authorization) {
        this.executionControl = Objects.requireNonNull(executionControl, "executionControl must not be null");
        this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "Execution Operator Service";
    }

    @Override
    public String description() {
        return "Operator control plane for managing and inspecting Andalus executions";
    }

    @Override
    public OperatorResult<List<ExecutionInfo>> list(OperatorContext context, ExecutionQuery query) {
        try {
            authorization.require(context, ExecutionOperatorPermissions.READ);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        try {
            ExecutionQuery effectiveQuery = (query == null) ? ExecutionQuery.all() : query;
            return OperatorResult.success(executionControl.list(effectiveQuery).stream()
                    .filter(info -> belongsToTenant(context, info))
                    .toList());
        } catch (Exception e) {
            return OperatorResult.failure("EXECUTION_LIST_FAILED", e.getMessage());
        }
    }

    @Override
    public OperatorResult<ExecutionInfo> inspect(OperatorContext context, String executionId) {
        try {
            authorization.require(context, ExecutionOperatorPermissions.INSPECT);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        if (executionId == null || executionId.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "executionId must not be blank");
        }

        try {
            Optional<ExecutionInfo> info = executionControl.find(executionId);
            if (info.isEmpty() || !belongsToTenant(context, info.get())) {
                return OperatorResult.failure("EXECUTION_NOT_FOUND", "No execution found with ID: " + executionId);
            }
            return OperatorResult.success(info.get());
        } catch (Exception e) {
            return OperatorResult.failure("EXECUTION_INSPECT_FAILED", e.getMessage());
        }
    }

    @Override
    public OperatorResult<Void> pause(OperatorContext context, String executionId) {
        try {
            authorization.require(context, ExecutionOperatorPermissions.PAUSE);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        return executeControl(context, executionId, "EXECUTION_PAUSE_FAILED", executionControl::pause);
    }

    @Override
    public OperatorResult<Void> resume(OperatorContext context, String executionId) {
        try {
            authorization.require(context, ExecutionOperatorPermissions.RESUME);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        return executeControl(context, executionId, "EXECUTION_RESUME_FAILED", executionControl::resume);
    }

    @Override
    public OperatorResult<Void> cancel(OperatorContext context, String executionId) {
        try {
            authorization.require(context, ExecutionOperatorPermissions.CANCEL);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        return executeControl(context, executionId, "EXECUTION_CANCEL_FAILED", executionControl::cancel);
    }

    @Override
    public OperatorResult<Void> retry(OperatorContext context, String executionId) {
        try {
            authorization.require(context, ExecutionOperatorPermissions.RETRY);
        } catch (OperatorAuthorizationException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        return executeControl(context, executionId, "EXECUTION_RETRY_FAILED", executionControl::retry);
    }

    private OperatorResult<Void> executeControl(
            OperatorContext context,
            String executionId,
            String errorCode,
            ThrowingConsumer<String> operation) {

        if (executionId == null || executionId.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "executionId must not be blank");
        }

        try {
            Optional<ExecutionInfo> execution = executionControl.find(executionId);
            if (execution.isEmpty() || !belongsToTenant(context, execution.get())) {
                return OperatorResult.failure("EXECUTION_NOT_FOUND", "No execution found with ID: " + executionId);
            }
            operation.accept(executionId);
            return OperatorResult.success(null);
        } catch (Exception e) {
            return OperatorResult.failure(errorCode, e.getMessage());
        }
    }

    private boolean belongsToTenant(OperatorContext context, ExecutionInfo execution) {
        return context != null && context.tenantId() != null && !context.tenantId().isBlank()
                && execution.tenantId() != null && execution.tenantId().equals(context.tenantId());
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}
