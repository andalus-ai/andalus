
package tech.kayys.andalus.execution.interaction;

import tech.kayys.andalus.agent.kernel.execution.AgentExecutionController;
import tech.kayys.andalus.agent.kernel.interaction.ApprovalRequest;
import tech.kayys.andalus.agent.kernel.interaction.InteractionController;
import tech.kayys.andalus.tool.ToolInvocation;

import java.util.Map;

public final class InteractionExecutionBridge {

    private final InteractionController interaction;
    private final AgentExecutionController execution;

    public InteractionExecutionBridge(
            InteractionController interaction,
            AgentExecutionController execution
    ) {
        this.interaction = interaction;
        this.execution = execution;
    }

    public ApprovalRequest requireApproval(
            String runId,
            ToolInvocation invocation,
            String reason
    ) {
        ApprovalRequest request =
                interaction.requestApproval(
                        runId,
                        reason,
                        invocation.name(),
                        Map.of(
                                "tool",
                                invocation.name(),
                                "arguments",
                                invocation.arguments()
                        )
                );

        execution.waitForApproval(
                runId,
                request.id()
        );

        return request;
    }
}
