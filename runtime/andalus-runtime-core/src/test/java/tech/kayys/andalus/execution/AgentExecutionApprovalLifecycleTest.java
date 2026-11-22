package tech.kayys.andalus.execution;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.tool.SimpleToolInvocation;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentExecutionApprovalLifecycleTest {

    private static AgentDecision.WaitForApproval approvalDecision() {
        return new AgentDecision.WaitForApproval(SimpleToolInvocation.of("publish", java.util.Map.of()));
    }

    @Test
    void rejectsApprovalUntilPendingToolLifecycleIsImplemented() {
        assertThrows(UnsupportedOperationException.class,
                () -> AgentExecutionService.requireApprovalLifecycle(approvalDecision()));
    }

    @Test
    void rejectsNonApprovalDecisions() {
        AgentDecision decision = new AgentDecision.ExecuteTool(
                SimpleToolInvocation.of("publish", java.util.Map.of()));

        assertThrows(IllegalArgumentException.class,
                () -> AgentExecutionService.requireApprovalLifecycle(decision));
    }
}
