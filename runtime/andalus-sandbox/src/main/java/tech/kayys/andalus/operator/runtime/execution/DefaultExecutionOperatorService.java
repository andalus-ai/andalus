package tech.kayys.andalus.operator.runtime.execution;

import tech.kayys.andalus.operator.runtime.DenyAllOperatorAuthorization;
import tech.kayys.andalus.spi.execution.ExecutionControl;
import tech.kayys.andalus.spi.execution.ExecutionInfo;
import tech.kayys.andalus.spi.execution.ExecutionQuery;
import tech.kayys.andalus.spi.operator.OperatorAuthorization;
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
        } catch (SecurityException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        try {
            ExecutionQuery effectiveQuery = (query == null) ? ExecutionQuery.all() : query;
            return OperatorResult.success(executionControl.list(effectiveQuery));
        } catch (Exception e) {
            return OperatorResult.failure("EXECUTION_LIST_FAILED", e.getMessage());
        }
    }

    @Override
    public OperatorResult<ExecutionInfo> inspect(OperatorContext context, String executionId) {
        try {
            authorization.require(context, ExecutionOperatorPermissions.INSPECT);
        } catch (SecurityException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        if (executionId == null || executionId.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "executionId must not be blank");
        }

        try {
            Optional<ExecutionInfo> info = executionControl.find(executionId);
            if (info.isEmpty()) {
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
        } catch (SecurityException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        return executeControl(executionId, "EXECUTION_PAUSE_FAILED", executionControl::pause);
    }

    @Override
    public OperatorResult<Void> resume(OperatorContext context, String executionId) {
        try {
            authorization.require(context, ExecutionOperatorPermissions.RESUME);
        } catch (SecurityException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        return executeControl(executionId, "EXECUTION_RESUME_FAILED", executionControl::resume);
    }

    @Override
    public OperatorResult<Void> cancel(OperatorContext context, String executionId) {
        try {
            authorization.require(context, ExecutionOperatorPermissions.CANCEL);
        } catch (SecurityException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        return executeControl(executionId, "EXECUTION_CANCEL_FAILED", executionControl::cancel);
    }

    @Override
    public OperatorResult<Void> retry(OperatorContext context, String executionId) {
        try {
            authorization.require(context, ExecutionOperatorPermissions.RETRY);
        } catch (SecurityException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        return executeControl(executionId, "EXECUTION_RETRY_FAILED", executionControl::retry);
    }

    private OperatorResult<Void> executeControl(
            String executionId,
            String errorCode,
            ThrowingConsumer<String> operation) {

        if (executionId == null || executionId.isBlank()) {
            return OperatorResult.failure("INVALID_ARGUMENT", "executionId must not be blank");
        }

        try {
            operation.accept(executionId);
            return OperatorResult.success(null);
        } catch (Exception e) {
            return OperatorResult.failure(errorCode, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}
