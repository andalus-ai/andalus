package tech.kayys.andalus.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import tech.kayys.andalus.agent.AgentRequest;
import tech.kayys.andalus.agent.AgentResponse;
import tech.kayys.andalus.core.runtime.AndalusRuntime;
import tech.kayys.andalus.execution.GraphExecutionContext;
import tech.kayys.andalus.execution.ExecutionEngine;
import tech.kayys.andalus.execution.ExecutionResult;
import tech.kayys.andalus.execution.SimpleGraphExecutionContext;
import tech.kayys.andalus.extension.Id;
import tech.kayys.andalus.extension.Metadata;
import tech.kayys.andalus.identity.ResourceId;
import tech.kayys.andalus.resource.BaseResource;
import tech.kayys.andalus.resource.ResourceType;

/**
 * Default CDI implementation of {@link AndalusRuntime}.
 *
 * <p>This is the top-level entry point for the Andalus execution stack.
 * The full call chain is:
 * <pre>
 *   DefaultAndalusRuntime
 *     → DefaultExecutionEngine
 *       → AgentExecutionService
 *         → DefaultAgentExecution
 *           → ReActAgent (real loop: model → tool pipeline → checkpoint → model …)
 *             → DefaultAgentToolExecutor (CB + retry + timeout)
 *               → Tool.execute()
 * </pre>
 *
 * <p>The response returned is the <em>actual</em> agent-generated content,
 * not a hard-coded "Execution completed successfully" string.
 */
@ApplicationScoped
public class DefaultAndalusRuntime extends BaseResource implements AndalusRuntime {

    private static final Logger LOG = Logger.getLogger(DefaultAndalusRuntime.class.getName());

    private static final Executor RUNTIME_POOL = Executors.newVirtualThreadPerTaskExecutor();

    public DefaultAndalusRuntime() {
        super(
            new ResourceId.CustomId(Id.random(), new ResourceType.Execution()),
            Metadata.builder()
                .name("DefaultAndalusRuntime")
                .description("Top-level Andalus agent execution runtime")
                .build()
        );
    }

    @Inject
    ExecutionEngine executionEngine;

    public void setExecutionEngine(ExecutionEngine executionEngine) {
        this.executionEngine = executionEngine;
    }

    // -------------------------------------------------------------------------
    // AndalusRuntime
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<AgentResponse> executeAsync(AgentDefinition agent, AgentRequest request) {
        return CompletableFuture.supplyAsync(() -> executeSync(agent, request), RUNTIME_POOL);
    }

    @Override
    public boolean supports(AgentDefinition agent) {
        // This runtime supports all agent definitions by default.
        // Future: check agent.type() and route to specialised runtimes.
        return agent != null;
    }

    // -------------------------------------------------------------------------
    // Synchronous path (used internally; also convenient for testing)
    // -------------------------------------------------------------------------

    public AgentResponse executeSync(AgentDefinition agent, AgentRequest request) {
        try {
            GraphExecutionContext context = SimpleGraphExecutionContext.fromRequest(request);
            ExecutionResult result   = executionEngine.executeAgent(agent, context);

            // Map ExecutionResult → AgentResponse, carrying the real content.
            if (result.getResult() instanceof AgentResponse r) {
                return r;
            }

            // Fallback mapping for non-AgentResponse result objects.
            String content = result.getResult() != null
                ? result.getResult().toString()
                : (result.getErrorMessage().orElse("No response from agent."));

            return AgentResponse.builder()
                .id(result.getExecutionId() != null ? result.getExecutionId().toString() : "")
                .success(result.isSuccess())
                .content(content)
                .error(result.getErrorMessage().orElse(null))
                .build();

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Runtime execution failed", e);
            return AgentResponse.builder()
                .success(false)
                .error("Runtime error: " + e.getMessage())
                .build();
        }
    }
}
