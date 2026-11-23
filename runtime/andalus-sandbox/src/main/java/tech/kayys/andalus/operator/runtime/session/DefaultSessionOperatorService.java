package tech.kayys.andalus.operator.runtime.session;

import tech.kayys.andalus.operator.runtime.DenyAllOperatorAuthorization;
import tech.kayys.andalus.spi.operator.OperatorAuthorization;
import tech.kayys.andalus.spi.operator.OperatorContext;
import tech.kayys.andalus.spi.operator.OperatorResult;
import tech.kayys.andalus.spi.operator.OperatorService;
import tech.kayys.andalus.spi.operator.session.SessionOperatorPermissions;
import tech.kayys.andalus.spi.operator.session.SessionOperatorService;
import tech.kayys.andalus.spi.session.SessionId;
import tech.kayys.andalus.spi.session.SessionInfo;
import tech.kayys.andalus.spi.session.SessionManager;
import tech.kayys.andalus.spi.session.SessionQuery;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class DefaultSessionOperatorService implements SessionOperatorService, OperatorService {

    public static final String ID = "operator.service.session";

    private final SessionManager sessionManager;
    private final OperatorAuthorization authorization;

    public DefaultSessionOperatorService(SessionManager sessionManager) {
        this(sessionManager, new DenyAllOperatorAuthorization());
    }

    public DefaultSessionOperatorService(
            SessionManager sessionManager,
            OperatorAuthorization authorization) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "Session Operator Service";
    }

    @Override
    public String description() {
        return "Operator control plane for managing and inspecting Andalus sessions";
    }

    @Override
    public OperatorResult<List<SessionInfo>> list(OperatorContext context, SessionQuery query) {
        try {
            authorization.require(context, SessionOperatorPermissions.READ);
        } catch (SecurityException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        try {
            SessionQuery effectiveQuery = (query == null) ? SessionQuery.all() : query;
            return OperatorResult.success(sessionManager.list(effectiveQuery));
        } catch (Exception e) {
            return OperatorResult.failure("SESSION_LIST_FAILED", e.getMessage());
        }
    }

    @Override
    public OperatorResult<SessionInfo> inspect(OperatorContext context, SessionId sessionId) {
        try {
            authorization.require(context, SessionOperatorPermissions.INSPECT);
        } catch (SecurityException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        if (sessionId == null) {
            return OperatorResult.failure("INVALID_ARGUMENT", "sessionId must not be null");
        }

        try {
            Optional<SessionInfo> session = sessionManager.find(sessionId);
            if (session.isEmpty()) {
                return OperatorResult.failure("SESSION_NOT_FOUND", "No session found with ID: " + sessionId);
            }
            return OperatorResult.success(session.get());
        } catch (Exception e) {
            return OperatorResult.failure("SESSION_INSPECT_FAILED", e.getMessage());
        }
    }

    @Override
    public OperatorResult<Void> suspend(OperatorContext context, SessionId sessionId) {
        try {
            authorization.require(context, SessionOperatorPermissions.SUSPEND);
        } catch (SecurityException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        return perform(sessionId, "SESSION_SUSPEND_FAILED", sessionManager::suspend);
    }

    @Override
    public OperatorResult<Void> resume(OperatorContext context, SessionId sessionId) {
        try {
            authorization.require(context, SessionOperatorPermissions.RESUME);
        } catch (SecurityException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        return perform(sessionId, "SESSION_RESUME_FAILED", sessionManager::resume);
    }

    @Override
    public OperatorResult<Void> close(OperatorContext context, SessionId sessionId) {
        try {
            authorization.require(context, SessionOperatorPermissions.CLOSE);
        } catch (SecurityException se) {
            return OperatorResult.failure("PERMISSION_DENIED", se.getMessage());
        }

        return perform(sessionId, "SESSION_CLOSE_FAILED", sessionManager::close);
    }

    private OperatorResult<Void> perform(
            SessionId sessionId,
            String errorCode,
            ThrowingSessionOperation operation) {

        if (sessionId == null) {
            return OperatorResult.failure("INVALID_ARGUMENT", "sessionId must not be null");
        }

        try {
            operation.accept(sessionId);
            return OperatorResult.success(null);
        } catch (Exception e) {
            return OperatorResult.failure(errorCode, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface ThrowingSessionOperation {
        void accept(SessionId id) throws Exception;
    }
}
