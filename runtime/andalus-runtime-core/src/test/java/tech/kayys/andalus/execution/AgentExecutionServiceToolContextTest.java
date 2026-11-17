package tech.kayys.andalus.execution;

import org.junit.jupiter.api.Test;

import tech.kayys.andalus.execution.governance.ToolBudget;
import tech.kayys.andalus.execution.governance.ToolBudgetLedger;

import static org.junit.jupiter.api.Assertions.*;

class AgentExecutionServiceToolContextTest {

    @Test
    void createsIndependentToolBudgetsForConcurrentExecutions() {
        ToolBudgetLedger ledger = new ToolBudgetLedger();
        ledger.register(new ToolBudget("tenant-a", null, 2, -1.0, -1));

        AgentExecutionService service = new AgentExecutionService();
        service.toolBudgetLedger = ledger;

        AgentToolExecutionContext first = service.createToolExecutionContext(
                new ExecutionIdentity("exec-1", "corr-1", null),
                null, "tenant-a", "user-1", java.util.Set.of("operator"), ExecutionBudget.balanced());
        AgentToolExecutionContext second = service.createToolExecutionContext(
                new ExecutionIdentity("exec-2", "corr-2", null),
                null, "tenant-a", "user-2", java.util.Set.of("reader"), ExecutionBudget.balanced());

        assertEquals(AgentToolExecutionContext.PrincipalType.USER, first.principalType());
        assertEquals("user-1", first.principalId());
        assertEquals("tenant-a", first.tenantId());
        assertTrue(first.roles().contains("operator"));
        assertFalse(second.roles().contains("operator"));
        assertNotSame(first.toolBudget(), second.toolBudget());

        assertTrue(first.toolBudget().consume(0, 0));
        assertTrue(first.toolBudget().consume(0, 0));
        assertTrue(first.toolBudget().isCallBudgetExhausted());
        assertFalse(second.toolBudget().isCallBudgetExhausted());
        assertTrue(second.toolBudget().consume(0, 0));
    }

    @Test
    void rolesCannotBeAttachedWithoutAnAuthenticatedUser() {
        AgentExecutionService service = new AgentExecutionService();
        assertThrows(IllegalArgumentException.class, () -> service.create(
                null, null, ExecutionBudget.balanced(), "tenant-a", null, java.util.Set.of("admin")));
    }

    @Test
    void standaloneExecutionIsExplicitlyAnonymousWithoutWildcardRoles() {
        AgentExecutionService service = new AgentExecutionService();

        AgentToolExecutionContext context = service.createToolExecutionContext(
                new ExecutionIdentity("exec-standalone", "corr-standalone", null),
                null, null, null, java.util.Set.of(), ExecutionBudget.balanced());

        assertEquals(AgentToolExecutionContext.PrincipalType.ANONYMOUS, context.principalType());
        assertNull(context.principalId());
        assertTrue(context.roles().isEmpty());
        assertEquals("unknown-agent", context.agentId());
    }
}
