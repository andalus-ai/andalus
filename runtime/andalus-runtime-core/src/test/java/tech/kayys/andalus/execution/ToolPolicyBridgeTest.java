package tech.kayys.andalus.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import tech.kayys.andalus.execution.governance.ToolExecutionDeniedException;
import tech.kayys.andalus.execution.governance.ToolBudget;
import java.time.Instant;
import java.util.Set;
import tech.kayys.andalus.tool.ToolInvocation;
import tech.kayys.andalus.tool.ToolInvocationId;
import tech.kayys.andalus.tool.ToolResult;

class ToolPolicyBridgeTest {

    private static ToolInvocation invocation(String name) {
        return new ToolInvocation() {
            public String name() { return name; }
            public Map<String, Object> arguments() { return Map.of("secret", "do-not-leak"); }
            public tech.kayys.andalus.identity.ResourceId id() { return new tech.kayys.andalus.identity.ResourceId.ToolId(tech.kayys.andalus.extension.Id.random()); }
            public tech.kayys.andalus.extension.Metadata metadata() { return tech.kayys.andalus.extension.Metadata.empty(); }
            public tech.kayys.andalus.resource.ResourceType type() { return tech.kayys.andalus.resource.ResourceType.fromString("tool"); }
        };
    }

    private static AgentToolExecutor returning(AgentDecision d) {
        return new AgentToolExecutor() {
            @Override
            public CompletionStage<AgentDecision> execute(ToolInvocation invocation) {
                return CompletableFuture.completedFuture(d);
            }
        };
    }

    private static AgentToolExecutionContext context() {
        return new AgentToolExecutionContext(
                "exec-test", "corr-test", null, "tenant-test", "user-test",
                "agent-test", AgentToolExecutionContext.PrincipalType.USER, "user-test",
                Set.of("operator"), ExecutionBudget.balanced(), ToolBudget.unlimited(),
                Map.of(), Map.of(), Instant.now());
    }

    private static final ToolResult RESULT = ToolResult.success(ToolInvocationId.generate(), "ok", java.time.Duration.ZERO, "test");

    @Test
    void completedResultIsReturnedWithoutRunningTheTool() throws Exception {
        var ran = new AtomicInteger();
        var inv = invocation("t");
        var bridge = ToolPolicyBridge.of(returning(new AgentDecision.ToolCompleted(inv, RESULT)), context(), null);
        assertSame(RESULT, bridge.executeViaPolicy(inv, () -> { ran.incrementAndGet(); return RESULT; }));
        assertEquals(0, ran.get());
    }

    @Test
    void executeToolDecisionRunsTheDirectFallbackOnce() throws Exception {
        var ran = new AtomicInteger();
        var inv = invocation("t");
        var bridge = ToolPolicyBridge.of(returning(new AgentDecision.ExecuteTool(inv)), context(), null);
        assertSame(RESULT, bridge.executeViaPolicy(inv, () -> { ran.incrementAndGet(); return RESULT; }));
        assertEquals(1, ran.get());
    }

    @Test
    void toolNeedingApprovalIsNotRunAndTheMessageNamesNoArguments() {
        var ran = new AtomicInteger();
        var inv = invocation("payments.refund");
        var bridge = ToolPolicyBridge.of(returning(new AgentDecision.WaitForApproval(inv)), context(), null);
        var e = assertThrows(ToolExecutionDeniedException.class,
                () -> bridge.executeViaPolicy(inv, () -> { ran.incrementAndGet(); return RESULT; }));
        assertEquals(0, ran.get());
        assertTrue(e.getMessage().contains("payments.refund"));
        assertFalse(e.getMessage().contains("do-not-leak"));
    }

    @Test
    void failedDecisionIsNotRun() {
        var ran = new AtomicInteger();
        var inv = invocation("t");
        var bridge = ToolPolicyBridge.of(returning(AgentDecision.fail("denied by policy")), context(), null);
        var e = assertThrows(RuntimeException.class, () -> bridge.executeViaPolicy(inv, () -> { ran.incrementAndGet(); return RESULT; }));
        assertEquals("denied by policy", e.getCause().getMessage());
        assertEquals(0, ran.get());
    }

    @Test
    void unexpectedDecisionIsNotRun() {
        var ran = new AtomicInteger();
        var inv = invocation("t");
        var bridge = ToolPolicyBridge.of(returning(new AgentDecision.Retry("later", 10)), context(), null);
        assertThrows(ToolExecutionDeniedException.class, () -> bridge.executeViaPolicy(inv, () -> { ran.incrementAndGet(); return RESULT; }));
        assertEquals(0, ran.get());
    }


    @Test
    void executionIdentityIsPassedToTheGovernedExecutor() throws Exception {
        var expected = context();
        var observed = new java.util.concurrent.atomic.AtomicReference<AgentToolExecutionContext>();
        AgentToolExecutor executor = new AgentToolExecutor() {
            @Override
            public CompletionStage<AgentDecision> execute(ToolInvocation invocation) {
                return CompletableFuture.completedFuture(new AgentDecision.ToolCompleted(invocation, RESULT));
            }

            @Override
            public CompletionStage<AgentDecision> execute(
                    ToolInvocation invocation,
                    AgentToolExecutionContext actual,
                    tech.kayys.andalus.tool.CancellationToken cancellation) {
                observed.set(actual);
                return CompletableFuture.completedFuture(new AgentDecision.ToolCompleted(invocation, RESULT));
            }
        };
        var invocation = invocation("tenant.read");
        var bridge = ToolPolicyBridge.of(executor, expected, null);
        assertSame(RESULT, bridge.executeViaPolicy(invocation, () -> fail("direct fallback must not run")));
        assertSame(expected, observed.get());
    }

    @Test
    void noExecutorMeansNoBridge() {
        assertNull(ToolPolicyBridge.of(null, context(), null));
    }
}
