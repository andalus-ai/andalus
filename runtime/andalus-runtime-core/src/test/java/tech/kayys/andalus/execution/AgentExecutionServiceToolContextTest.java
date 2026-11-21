package tech.kayys.andalus.execution;

import org.junit.jupiter.api.Test;

import tech.kayys.andalus.execution.governance.ToolBudget;
import tech.kayys.andalus.execution.governance.quota.ToolBudgetLedger;

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

    @Test
    void workspaceRootCannotBeOverriddenByExecutionAttributes() throws Exception {
        java.nio.file.Path configuredRoot = java.nio.file.Files.createTempDirectory("andalus-workspace-configured");
        java.nio.file.Path untrustedRoot = java.nio.file.Files.createTempDirectory("andalus-workspace-attribute");

        AgentExecutionService service = new AgentExecutionService();
        AgentToolExecutionContext base = service.createToolExecutionContext(
                new ExecutionIdentity("exec-workspace", "corr-workspace", null),
                null, "tenant-a", "user-1", java.util.Set.of("reader"), ExecutionBudget.balanced());
        AgentToolExecutionContext context = new AgentToolExecutionContext(
                base.executionId(), base.correlationId(), base.parentExecutionId(),
                base.tenantId(), base.userId(), base.agentId(), base.principalType(),
                base.principalId(), base.roles(), base.executionBudget(), base.toolBudget(),
                java.util.Map.of("andalus.workspace.root", configuredRoot),
                java.util.Map.of("andalus.workspace.root", untrustedRoot), base.createdAt());

        tech.kayys.andalus.tool.ToolContext toolContext = DefaultAgentToolExecutor.toolContextFor(context);

        assertEquals(configuredRoot, toolContext.getAttribute("andalus.workspace.root").orElseThrow());
    }

    @Test
    void workspaceRootIsNotAcceptedFromExecutionAttributesAlone() {
        AgentExecutionService service = new AgentExecutionService();
        AgentToolExecutionContext base = service.createToolExecutionContext(
                new ExecutionIdentity("exec-no-workspace", "corr-no-workspace", null),
                null, "tenant-a", "user-1", java.util.Set.of("reader"), ExecutionBudget.balanced());
        AgentToolExecutionContext context = new AgentToolExecutionContext(
                base.executionId(), base.correlationId(), base.parentExecutionId(),
                base.tenantId(), base.userId(), base.agentId(), base.principalType(),
                base.principalId(), base.roles(), base.executionBudget(), base.toolBudget(),
                java.util.Map.of(),
                java.util.Map.of("andalus.workspace.root", java.nio.file.Path.of(".")), base.createdAt());

        tech.kayys.andalus.tool.ToolContext toolContext = DefaultAgentToolExecutor.toolContextFor(context);

        assertTrue(toolContext.getAttribute("andalus.workspace.root").isEmpty());
    }

}
