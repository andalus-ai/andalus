package tech.kayys.andalus.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentExecutionApprovalLifecycleTest {

    @Test
    void rejectsApprovalUntilPendingToolLifecycleIsImplemented() {
        AgentDecision decision = new AgentDecision.WaitForApproval("tool requires approval");

        assertThrows(UnsupportedOperationException.class,
                () -> AgentExecutionService.requireApprovalLifecycle(decision));
    }

    @Test
    void rejectsNonApprovalDecisions() {
        AgentDecision decision = new AgentDecision.ExecuteTool();

        assertThrows(IllegalArgumentException.class,
                () -> AgentExecutionService.requireApprovalLifecycle(decision));
    }
}
