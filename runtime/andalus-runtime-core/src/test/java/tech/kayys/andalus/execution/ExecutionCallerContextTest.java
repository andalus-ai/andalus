package tech.kayys.andalus.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.andalus.agent.AgentRequest;

class ExecutionCallerContextTest {

    @Test
    void trustedCallerContextNormalizesAndSnapshotsIdentity() {
        ExecutionCallerContext caller = new ExecutionCallerContext(
                " tenant-a ", " user-1 ", Set.of("admin", "operator"));

        assertEquals("tenant-a", caller.tenantId());
        assertEquals("user-1", caller.userId());
        assertEquals(Set.of("admin", "operator"), caller.roles());
        assertThrows(UnsupportedOperationException.class, () -> caller.roles().add("other"));
    }

    @Test
    void rolesRequireAnAuthenticatedUser() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionCallerContext("tenant-a", null, Set.of("admin")));
    }

    @Test
    void blankIdentityValuesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionCallerContext(" ", "user-1", Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionCallerContext("tenant-a", " ", Set.of()));
    }

    @Test
    void requestContextPreservesTrustedCallerAcrossChildContexts() {
        ExecutionCallerContext caller = new ExecutionCallerContext(
                "tenant-a", "user-1", Set.of("operator"));
        AgentRequest request = AgentRequest.builder().content("hello").build();

        SimpleGraphExecutionContext context = SimpleGraphExecutionContext.fromRequest(request, caller);
        GraphExecutionContext child = context.createChild(java.util.Map.of("untrustedTenant", "tenant-b"));

        assertEquals(caller, context.callerContext());
        assertEquals(caller, ((SimpleGraphExecutionContext) child).callerContext());
        assertEquals("hello", context.prompt().orElseThrow());
    }

    @Test
    void legacyRequestFactoryRemainsAnonymous() {
        SimpleGraphExecutionContext context = SimpleGraphExecutionContext.fromRequest(
                AgentRequest.builder().content("hello").build());

        assertEquals(ExecutionCallerContext.anonymous(), context.callerContext());
    }
}
