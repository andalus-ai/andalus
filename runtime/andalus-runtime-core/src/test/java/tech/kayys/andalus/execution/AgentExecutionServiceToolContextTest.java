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

    @Test
    void trustedCallerContextCarriesRolesAndPermissionsIntoToolExecution() {
        AgentExecutionService service = new AgentExecutionService();
        AgentCallerContext caller = AgentCallerContext.user(
                "tenant-a", "user-a", java.util.Set.of("agent:run"),
                java.util.Set.of("tools:read"));

        AgentToolExecutionContext context = service.createToolExecutionContext(
                new ExecutionIdentity("exec-trusted", "corr-trusted", null),
                null, caller, ExecutionBudget.balanced());

        assertEquals("tenant-a", context.tenantId());
        assertEquals("user-a", context.userId());
        assertEquals(AgentToolExecutionContext.PrincipalType.USER, context.principalType());
        assertTrue(context.roles().contains("agent:run"));
        assertTrue(context.permissions().contains("tools:read"));
    }

    @Test
    void resumeUsesCurrentCallerClaimsRatherThanCheckpointedGrants() {
        AgentExecutionService service = new AgentExecutionService();
        java.util.Map<String, Object> ownership = java.util.Map.of(
                "tenantId", "tenant-a",
                "userId", "user-a",
                "principalType", "USER",
                "principalId", "user-a");

        AgentCallerContext currentCaller = AgentCallerContext.user(
                "tenant-a", "user-a", java.util.Set.of("agent:resume"),
                java.util.Set.of("tools:read"));

        assertDoesNotThrow(() -> AgentExecutionService.verifyResumeIdentity(ownership, currentCaller));

        AgentToolExecutionContext resumed = service.createToolExecutionContext(
                new ExecutionIdentity("exec-resume", "corr-resume", null),
                null, currentCaller, ExecutionBudget.balanced());

        assertEquals(java.util.Set.of("agent:resume"), resumed.roles());
        assertEquals(java.util.Set.of("tools:read"), resumed.permissions());
        assertFalse(resumed.roles().contains("admin"));
        assertFalse(resumed.permissions().contains("tools:write"));
    }


    @Test
    void resolvesWorkspaceFromAuthenticatedCallerAndAgentIdentity() throws Exception {
        java.nio.file.Path workspace = java.nio.file.Files.createTempDirectory("andalus-resolved-workspace");
        @SuppressWarnings("unchecked")
        jakarta.enterprise.inject.Instance<WorkspaceResolver> resolvers =
                org.mockito.Mockito.mock(jakarta.enterprise.inject.Instance.class);
        org.mockito.Mockito.when(resolvers.isUnsatisfied()).thenReturn(false);
        org.mockito.Mockito.when(resolvers.get()).thenReturn(request -> {
            assertEquals(AgentCallerContext.PrincipalType.USER, request.principalType());
            assertEquals("tenant-a", request.tenantId());
            assertEquals("user-a", request.principalId());
            assertEquals("agent-a", request.agentId());
            return java.util.Optional.of(workspace);
        });

        AgentExecutionService service = new AgentExecutionService();
        service.workspaceResolverInstances = resolvers;
        AgentCallerContext caller = AgentCallerContext.user(
                "tenant-a", "user-a", java.util.Set.of("agent:run"), java.util.Set.of("workspace:read"));

        AgentToolExecutionContext context = service.createToolExecutionContext(
                new ExecutionIdentity("exec-workspace-resolver", "corr-workspace-resolver", null),
                null, "agent-a", caller, ExecutionBudget.balanced());

        assertEquals(workspace.toRealPath(), context.constraints().get("andalus.workspace.root"));
    }

    @Test
    void workspaceResolutionFailureDoesNotGrantAnUntrustedFallback() throws Exception {
        java.nio.file.Path notDirectory = java.nio.file.Files.createTempFile("andalus-workspace", ".txt");
        @SuppressWarnings("unchecked")
        jakarta.enterprise.inject.Instance<WorkspaceResolver> resolvers =
                org.mockito.Mockito.mock(jakarta.enterprise.inject.Instance.class);
        org.mockito.Mockito.when(resolvers.isUnsatisfied()).thenReturn(false);
        org.mockito.Mockito.when(resolvers.get()).thenReturn(request -> java.util.Optional.of(notDirectory));

        AgentExecutionService service = new AgentExecutionService();
        service.workspaceResolverInstances = resolvers;
        AgentCallerContext caller = AgentCallerContext.user(
                "tenant-a", "user-a", java.util.Set.of(), java.util.Set.of());

        AgentToolExecutionContext context = service.createToolExecutionContext(
                new ExecutionIdentity("exec-workspace-denied", "corr-workspace-denied", null),
                null, "agent-a", caller, ExecutionBudget.balanced());

        assertFalse(context.constraints().containsKey("andalus.workspace.root"));
    }

}
