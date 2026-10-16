package tech.kayys.andalus.agent.integration.gamelan;

import io.quarkus.arc.log.LoggerName;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.sdk.client.GamelanClient;
import tech.kayys.andalus.agent.core.memory.AgentMemoryService;

import java.time.Instant;
import java.util.*;

/**
 * Bridge service connecting Gamelan workflow orchestration with Andalus-Aqli agents.
 *
 * Enables agents to:
 * - Create and execute workflows as part of reasoning
 * - Monitor workflow progress and retrieve results
 * - Send signals to running workflows
 * - Store workflow interactions in agent memory
 *
 * Thread-safe and reactive (Mutiny-based).
 * All operations are non-blocking and support composition.
 */
@ApplicationScoped
public class AgentGamelanService {

    @LoggerName("tech.kayys.andalus.agent.core.gamelan")
    Logger logger;

    @Inject
    GamelanClient gamelanClient;

    @Inject
    AgentMemoryService memoryService;

    /**
     * Creates and starts a workflow run with agent context.
     *
     * Injects agent ID and other context into workflow inputs, creating
     * a rich execution environment where the workflow knows it was invoked
     * by an agent.
     *
     * @param agentId agent invoking the workflow
     * @param workflowId workflow definition to execute
     * @param inputs workflow inputs/parameters
     * @return workflow run response with run ID and initial status
     */
    public Uni<RunResponse> createAndStartWorkflow(
            String agentId,
            String workflowId,
            Map<String, Object> inputs) {

        logger.infof("Agent %s creating workflow run for %s", agentId, workflowId);

        // Enrich inputs with agent context
        Map<String, Object> enrichedInputs = new HashMap<>(inputs != null ? inputs : Map.of());
        enrichedInputs.put("_agentId", agentId);
        enrichedInputs.put("_initiatedAt", Instant.now().toString());

        // Get agent memory context if available
        return memoryService.getContextPrompt(agentId, 5)
            .map(context -> {
                enrichedInputs.put("_agentContext", context);
                return enrichedInputs;
            })
            .onFailure().recoverWithItem(() -> {
                // Proceed without memory context if error
                logger.warnf("Could not enrich workflow with agent memory for %s", agentId);
                return enrichedInputs;
            })
            // Create and start workflow
            .flatMap(finalInputs -> gamelanClient.runs()
                .create(workflowId)
                .inputs(finalInputs)
                .label("agentId", agentId)
                .label("initiatedAt", Instant.now().toString())
                .executeAndStart())
            // Store in agent memory
            .flatMap(runResponse -> {
                logger.infof("Workflow %s started with runId=%s by agent %s",
                    workflowId, runResponse.getRunId(), agentId);

                // Store in memory for audit/learning
                return storeWorkflowInMemory(agentId, runResponse)
                    .map(v -> runResponse);
            })
            .onFailure().invoke(failure ->
                logger.errorf(failure, "Failed to create workflow run for agent %s", agentId)
            );
    }

    /**
     * Creates a workflow run but does not start it.
     * Use {@link #startWorkflow(String)} to begin execution.
     *
     * @param agentId agent creating the workflow
     * @param workflowId workflow definition
     * @param inputs workflow inputs
     * @return created but not-yet-started workflow run
     */
    public Uni<RunResponse> createWorkflow(
            String agentId,
            String workflowId,
            Map<String, Object> inputs) {

        Map<String, Object> enrichedInputs = new HashMap<>(inputs != null ? inputs : Map.of());
        enrichedInputs.put("_agentId", agentId);
        enrichedInputs.put("_createdAt", Instant.now().toString());

        return gamelanClient.runs()
            .create(workflowId)
            .inputs(enrichedInputs)
            .label("agentId", agentId)
            .execute()
            .onFailure().invoke(failure ->
                logger.errorf(failure, "Failed to create workflow %s for agent %s", workflowId, agentId)
            );
    }

    /**
     * Starts a previously created workflow run.
     *
     * @param runId ID of the workflow run to start
     * @return updated workflow run response
     */
    public Uni<RunResponse> startWorkflow(String runId) {
        logger.infof("Starting workflow run %s", runId);

        return gamelanClient.runs()
            .start(runId)
            .onFailure().invoke(failure ->
                logger.errorf(failure, "Failed to start workflow run %s", runId)
            );
    }

    /**
     * Retrieves the current status and details of a workflow run.
     *
     * @param runId ID of the workflow run
     * @return current run response with status, progress, etc.
     */
    public Uni<RunResponse> getWorkflowStatus(String runId) {
        return gamelanClient.runs()
            .get(runId)
            .onFailure().invoke(failure ->
                logger.errorf(failure, "Failed to get status for workflow run %s", runId)
            );
    }

    /**
     * Retrieves the complete execution history (event log) of a workflow run.
     * Useful for debugging, auditing, and understanding workflow execution flow.
     *
     * @param runId ID of the workflow run
     * @return execution history containing all events in order
     */
    public Uni<ExecutionHistory> getWorkflowHistory(String runId) {
        logger.infof("Retrieving execution history for workflow run %s", runId);

        return gamelanClient.runs()
            .getHistory(runId)
            .onFailure().invoke(failure ->
                logger.errorf(failure, "Failed to get history for workflow run %s", runId)
            );
    }

    /**
     * Suspends a running workflow execution.
     * The workflow can later be resumed with {@link #resumeWorkflow(String, Map)}.
     *
     * @param runId ID of the workflow run to suspend
     * @param reason reason for suspension (stored in audit trail)
     * @return void completion uni
     */
    public Uni<Void> suspendWorkflow(String runId, String reason) {
        logger.infof("Suspending workflow run %s. Reason: %s", runId, reason);

        return gamelanClient.runs()
            .suspend(runId)
            .reason(reason)
            .execute()
            .replaceWithVoid()
            .onFailure().invoke(failure ->
                logger.errorf(failure, "Failed to suspend workflow run %s", runId)
            );
    }

    /**
     * Resumes a suspended workflow execution.
     * Optionally provide data to be injected back into the workflow.
     *
     * @param runId ID of the workflow run to resume
     * @param resumeData data to inject into the workflow
     * @return updated workflow run response
     */
    public Uni<RunResponse> resumeWorkflow(String runId, Map<String, Object> resumeData) {
        logger.infof("Resuming workflow run %s", runId);

        return gamelanClient.runs()
            .resume(runId)
            .data(resumeData == null ? Map.of() : resumeData)
            .execute()
            .onFailure().invoke(failure ->
                logger.errorf(failure, "Failed to resume workflow run %s", runId)
            );
    }

    /**
     * Cancels a running or suspended workflow execution.
     * Cannot be undone; use with caution.
     *
     * @param runId ID of the workflow run to cancel
     * @param reason reason for cancellation (stored in audit trail)
     * @return void completion uni
     */
    public Uni<Void> cancelWorkflow(String runId, String reason) {
        logger.infof("Cancelling workflow run %s. Reason: %s", runId, reason);

        return gamelanClient.runs()
            .cancel(runId, reason)
            .onFailure().invoke(failure ->
                logger.errorf(failure, "Failed to cancel workflow run %s", runId)
            );
    }

    /**
     * Sends a signal to a running workflow.
     * Signals are used for workflow-agent communication and can trigger
     * specific actions or resume suspended workflows.
     *
     * @param runId ID of the workflow run
     * @param signalName name of the signal
     * @param signalData data to send with the signal
     * @return void completion uni
     */
    public Uni<Void> signalWorkflow(
            String runId,
            String signalName,
            Map<String, Object> signalData) {

        logger.infof("Sending signal '%s' to workflow run %s", signalName, runId);

        return gamelanClient.runs()
            .signal(runId)
            .name(signalName)
            .payload(signalData == null ? Map.of() : signalData)
            .execute()
            .onFailure().invoke(failure ->
                logger.errorf(failure, "Failed to signal workflow run %s", runId)
            );
    }

    /**
     * Retrieves the current count of active (running or suspended) workflows.
     *
     * @return count of active workflow runs
     */
    public Uni<Long> getActiveWorkflowCount() {
        return gamelanClient.runs()
            .getActiveCount()
            .onFailure().invoke(failure ->
                logger.errorf(failure, "Failed to get active workflow count")
            );
    }

    /**
     * Stores a workflow execution in agent memory for learning and audit.
     *
     * @param agentId agent that executed the workflow
     * @param runResponse the workflow run response
     * @return void completion uni
     */
    private Uni<Void> storeWorkflowInMemory(String agentId, RunResponse runResponse) {
        String message = String.format(
            "Workflow '%s' (v%s) executed by agent. RunId: %s. Status: %s. Duration: %dms",
            runResponse.getWorkflowId(),
            runResponse.getWorkflowVersion(),
            runResponse.getRunId(),
            runResponse.getStatus(),
            runResponse.getDurationMs() != null ? runResponse.getDurationMs() : 0
        );

        return memoryService.storeInteraction(
            agentId,
            null,  // No specific session
            null,  // No specific user
            "Workflow execution: " + runResponse.getWorkflowId(),
            message
        ).onFailure().recoverWithItem((Void) null);
    }

    /**
     * Binds an agent to a workflow, indicating a recurring relationship.
     * Useful for agents that repeatedly use the same workflow.
     *
     * @param agentId agent ID
     * @param workflowId workflow ID
     */
    public void bindAgentToWorkflow(String agentId, String workflowId) {
        logger.infof("Binding agent %s to workflow %s", agentId, workflowId);
        // Could store in memory or configuration as needed
    }

    /**
     * Unbinds an agent from a workflow.
     *
     * @param agentId agent ID
     * @param workflowId workflow ID
     */
    public void unbindAgentFromWorkflow(String agentId, String workflowId) {
        logger.infof("Unbinding agent %s from workflow %s", agentId, workflowId);
    }

    /**
     * Gets all workflows bound to an agent.
     *
     * @param agentId agent ID
     * @return list of bound workflow IDs
     */
    public Uni<List<String>> getBoundWorkflows(String agentId) {
        logger.infof("Getting bound workflows for agent %s", agentId);
        // Retrieve from memory or configuration
        return Uni.createFrom().item(List.of());
    }

    /**
     * Gets metrics about workflow execution for an agent.
     * Useful for learning and optimization.
     *
     * @param agentId agent ID
     * @return workflow metrics (success rate, avg duration, etc.)
     */
    public Uni<WorkflowMetrics> getMetrics(String agentId) {
        logger.infof("Getting workflow metrics for agent %s", agentId);

        return gamelanClient.runs()
            .query()
            .size(100)
            .execute()
            .map(runs -> runs.stream()
                .filter(run -> agentId.equals(agentMarker(run)))
                .toList())
            .map(runs -> {
                WorkflowMetrics metrics = new WorkflowMetrics();
                metrics.setTotalRuns(runs.size());
                metrics.setCompletedRuns((int) runs.stream()
                    .filter(r -> "COMPLETED".equals(r.getStatus()))
                    .count());
                metrics.setFailedRuns((int) runs.stream()
                    .filter(r -> "FAILED".equals(r.getStatus()))
                    .count());
                metrics.setAverageDurationMs(runs.stream()
                    .mapToLong(r -> r.getDurationMs() != null ? r.getDurationMs() : 0)
                    .average()
                    .orElse(0.0));
                metrics.setSuccessRate(metrics.getTotalRuns() > 0
                    ? (double) metrics.getCompletedRuns() / metrics.getTotalRuns()
                    : 0.0);
                return metrics;
            })
            .onFailure().recoverWithItem(() -> {
                logger.warnf("Could not calculate metrics for agent %s", agentId);
                return new WorkflowMetrics();
            });
    }

    private Object agentMarker(RunResponse run) {
        Map<String, Object> outputs = run.getOutputs();
        if (outputs == null) {
            return null;
        }
        Object explicitAgentId = outputs.get("agentId");
        return explicitAgentId != null ? explicitAgentId : outputs.get("_agentId");
    }
}
