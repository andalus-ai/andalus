package tech.kayys.andalus.operator;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.operator.runtime.PermissiveOperatorAuthorization;
import tech.kayys.andalus.operator.runtime.execution.DefaultExecutionOperatorService;
import tech.kayys.andalus.operator.runtime.execution.InMemoryExecutionControl;
import tech.kayys.andalus.operator.runtime.session.DefaultSessionOperatorService;
import tech.kayys.andalus.operator.runtime.session.InMemorySessionManager;
import tech.kayys.andalus.spi.execution.ExecutionInfo;
import tech.kayys.andalus.spi.execution.ExecutionQuery;
import tech.kayys.andalus.spi.execution.ExecutionState;
import tech.kayys.andalus.spi.operator.OperatorContext;
import tech.kayys.andalus.spi.operator.OperatorResult;
import tech.kayys.andalus.spi.session.SessionId;
import tech.kayys.andalus.spi.session.SessionInfo;
import tech.kayys.andalus.spi.session.SessionQuery;
import tech.kayys.andalus.spi.session.SessionState;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OperatorTenantResourceIsolationTest {

    private final OperatorContext tenantA =
            new OperatorContext("tenant-a", "user-a", "corr-a", "req-a", Map.of());

    @Test
    void executionReadsAndMutationsAreBoundToTenant() {
        InMemoryExecutionControl control = new InMemoryExecutionControl();
        control.register(new ExecutionInfo(
                "execution-b", "tenant-b", "user-b", "agent-b", null,
                ExecutionState.RUNNING, Instant.now(), Instant.now(), null, Instant.now(),
                "corr-b", null, null, Map.of()));

        DefaultExecutionOperatorService service =
                new DefaultExecutionOperatorService(control, new PermissiveOperatorAuthorization());

        OperatorResult<ExecutionInfo> inspect = service.inspect(tenantA, "execution-b");
        assertInstanceOf(OperatorResult.Failure.class, inspect);
        assertEquals("EXECUTION_NOT_FOUND", ((OperatorResult.Failure<?>) inspect).code());

        OperatorResult<Void> pause = service.pause(tenantA, "execution-b");
        assertInstanceOf(OperatorResult.Failure.class, pause);
        assertEquals("EXECUTION_NOT_FOUND", ((OperatorResult.Failure<?>) pause).code());

        OperatorResult<List<ExecutionInfo>> list = service.list(tenantA, ExecutionQuery.all());
        assertInstanceOf(OperatorResult.Success.class, list);
        assertTrue(((OperatorResult.Success<List<ExecutionInfo>>) list).value().isEmpty());
        assertEquals(ExecutionState.RUNNING, control.find("execution-b").orElseThrow().state());
    }

    @Test
    void sessionReadsAndMutationsAreBoundToTenant() {
        InMemorySessionManager manager = new InMemorySessionManager();
        SessionId foreignId = SessionId.of("session-b");
        manager.register(new SessionInfo(
                foreignId, "tenant-b", "user-b", "agent-b", SessionState.ACTIVE,
                Instant.now(), Instant.now(), null, "corr-b", List.of(), Map.of()));

        DefaultSessionOperatorService service =
                new DefaultSessionOperatorService(manager, new PermissiveOperatorAuthorization());

        OperatorResult<SessionInfo> inspect = service.inspect(tenantA, foreignId);
        assertInstanceOf(OperatorResult.Failure.class, inspect);
        assertEquals("SESSION_NOT_FOUND", ((OperatorResult.Failure<?>) inspect).code());

        OperatorResult<Void> close = service.close(tenantA, foreignId);
        assertInstanceOf(OperatorResult.Failure.class, close);
        assertEquals("SESSION_NOT_FOUND", ((OperatorResult.Failure<?>) close).code());

        OperatorResult<List<SessionInfo>> list = service.list(tenantA, SessionQuery.all());
        assertInstanceOf(OperatorResult.Success.class, list);
        assertTrue(((OperatorResult.Success<List<SessionInfo>>) list).value().isEmpty());
        assertEquals(SessionState.ACTIVE, manager.find(foreignId).orElseThrow().state());
    }
}
