package tech.kayys.andalus.execution;

import tech.kayys.andalus.agent.react.BaseReActAgent;
import tech.kayys.andalus.execution.governance.ToolExecutionDeniedException;
import tech.kayys.andalus.tool.CancellationToken;

/**
 * Routes a tool call of the ReAct loop through the governed {@link AgentToolExecutor} and decides what each decision means for the loop.
 *
 * <p>The loop hands the bridge a {@code directFallback} that runs the tool <em>directly</em>, bypassing every policy. It must therefore only be
 * used when the executor itself says to ({@link AgentDecision.ExecuteTool}). Every other decision that is not a completed result is a refusal:
 * a tool that needs approval is <strong>not</strong> run (this execution has no way to wait for an approver and resume the call), and a decision the
 * loop does not understand is not run either. The message names the tool and never includes its arguments.
 */
final class ToolPolicyBridge {

    private ToolPolicyBridge() {
    }

    static BaseReActAgent.ToolExecutorBridge of(AgentToolExecutor executor, CancellationToken cancellation) {
        if (executor == null) {
            return null;
        }
        return (invocation, directFallback) -> {
            AgentDecision decision = executor.execute(invocation, cancellation).toCompletableFuture().get();
            return switch (decision) {
                case AgentDecision.ToolCompleted tc -> tc.result();
                case AgentDecision.ExecuteTool et -> directFallback.get();
                case AgentDecision.Fail f -> throw new RuntimeException(f.error());
                case AgentDecision.WaitForApproval wa -> throw new ToolExecutionDeniedException(
                        "Tool " + invocation.name() + " requires approval and was not run: this execution cannot wait for an approval");
                default -> throw new ToolExecutionDeniedException(
                        "Tool " + invocation.name() + " was not run: the tool pipeline returned an unexpected decision ("
                                + decision.getClass().getSimpleName() + ")");
            };
        };
    }
}
