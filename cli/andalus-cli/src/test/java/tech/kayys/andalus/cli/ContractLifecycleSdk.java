package tech.kayys.andalus.cli;

import tech.kayys.andalus.agent.run.AgentRunCancelResult;
import tech.kayys.andalus.agent.event.AgentRunEvent;
import tech.kayys.andalus.agent.event.AgentRunEvents;
import tech.kayys.andalus.agent.event.AgentRunEventsFollowOptions;
import tech.kayys.andalus.agent.event.AgentRunEventsFollowResult;
import tech.kayys.andalus.agent.event.AgentRunEventsQuery;
import tech.kayys.andalus.agent.run.AgentRunForgetResult;
import tech.kayys.andalus.agent.run.AgentRunHandle;
import tech.kayys.andalus.agent.history.AgentRunHistory;
import tech.kayys.andalus.agent.history.AgentRunHistoryQuery;
import tech.kayys.andalus.agent.run.AgentRunPreview;
import tech.kayys.andalus.agent.run.AgentRunReadiness;
import tech.kayys.andalus.agent.run.AgentRunRequest;
import tech.kayys.andalus.agent.run.AgentRunResult;
import tech.kayys.andalus.agent.skill.AgentRunSkillAssessment;
import tech.kayys.andalus.agent.run.AgentRunState;
import tech.kayys.andalus.agent.run.AgentRunStatus;
import tech.kayys.andalus.agent.run.AgentRunWaitOptions;
import tech.kayys.andalus.agent.run.AgentRunWaitResult;
import tech.kayys.andalus.harness.HarnessPlan;
import tech.kayys.andalus.harness.HarnessPlanRequest;
import tech.kayys.andalus.client.ProductSurface;
import tech.kayys.andalus.policy.SurfacePolicyAssessment;
import tech.kayys.andalus.client.AndalusAqliSdk;
import tech.kayys.andalus.client.AndalusPlatformStatus;
import tech.kayys.andalus.client.AndalusWorkbenchModel;
import tech.kayys.andalus.client.WorkspaceInspectionRequest;
import tech.kayys.andalus.client.WorkspaceSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class ContractLifecycleSdk implements AndalusAqliSdk {
    static final String RUN_ID = "contract-run-1";

    private static final String STRATEGY = "contract-strategy";

    private final AndalusAqliSdk delegate = AndalusAqliSdk.local();

    @Override
    public AndalusPlatformStatus status() {
        return delegate.status();
    }

    @Override
    public List<ProductSurface> productSurfaces() {
        return delegate.productSurfaces();
    }

    @Override
    public AndalusWorkbenchModel workbench() {
        return delegate.workbench();
    }

    @Override
    public WorkspaceSnapshot inspectWorkspace(WorkspaceInspectionRequest request) {
        return delegate.inspectWorkspace(request);
    }

    @Override
    public HarnessPlan planHarness(HarnessPlanRequest request) {
        return delegate.planHarness(request);
    }

    @Override
    public AgentRunReadiness assessRunReadiness(AgentRunRequest request) {
        return new AgentRunReadiness("coding-agent", true, readySurface(), readySkills());
    }

    @Override
    public AgentRunPreview previewRun(AgentRunRequest request) {
        AgentRunRequest normalized = AgentRunRequest.builder(request).build();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("surfaceId", normalized.surfaceId());
        context.put("workspace", normalized.workspacePath());
        context.put("harness", "contract-harness");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("source", "contract");
        return new AgentRunPreview(
                "contract-preview-1",
                normalized.tenantId(),
                normalized.modelId(),
                normalized.workflowId(),
                normalized.surfaceId(),
                normalized.sessionId(),
                normalized.userId(),
                !normalized.systemPrompt().isBlank(),
                normalized.prompt().length(),
                normalized.systemPrompt().length(),
                normalized.memoryEnabled(),
                normalized.maxSteps(),
                normalized.skills(),
                context,
                parameters,
                readySurface(),
                readySkills());
    }

    @Override
    public AgentRunResult run(AgentRunRequest request) {
        return new AgentRunResult(
                RUN_ID,
                "Contract answer.",
                true,
                STRATEGY,
                List.of("contract-step"),
                lifecycleMetadata());
    }

    @Override
    public AgentRunStatus runStatus(String runId) {
        return statusSnapshot();
    }

    @Override
    public AgentRunHistory runHistory(AgentRunHistoryQuery query) {
        return new AgentRunHistory(
                query,
                List.of(statusSnapshot()),
                1,
                "Recorded run statuses.");
    }

    @Override
    public AgentRunEvents runEvents(String runId, AgentRunEventsQuery query) {
        String normalizedRunId = normalizeRunId(runId);
        return new AgentRunEvents(
                normalizedRunId,
                query,
                List.of(event(normalizedRunId)),
                1,
                "Recorded run events.");
    }

    @Override
    public AgentRunEventsFollowResult followRunEvents(
            String runId,
            AgentRunEventsFollowOptions options,
            Consumer<AgentRunEvents> eventConsumer) {
        AgentRunEventsFollowOptions normalized = options == null
                ? AgentRunEventsFollowOptions.defaults()
                : options;
        AgentRunEvents events = runEvents(runId, normalized.query());
        if (eventConsumer != null) {
            eventConsumer.accept(events);
        }
        AgentRunEventsQuery nextQuery = new AgentRunEventsQuery(
                normalized.query().state(),
                normalized.query().type(),
                events.nextAfterSequence(),
                normalized.query().limit());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("maxPolls", normalized.maxPolls());
        metadata.put("pollMillis", normalized.pollMillis());
        return new AgentRunEventsFollowResult(
                normalizeRunId(runId),
                normalized.query(),
                nextQuery,
                events,
                true,
                false,
                1,
                0,
                "Run events reached terminal state: completed.",
                metadata);
    }

    @Override
    public AgentRunWaitResult waitForRun(String runId, AgentRunWaitOptions options) {
        AgentRunWaitOptions normalized = options == null ? AgentRunWaitOptions.defaults() : options;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("timeoutMillis", normalized.timeoutMillis());
        metadata.put("pollMillis", normalized.pollMillis());
        return new AgentRunWaitResult(
                normalizeRunId(runId),
                statusSnapshot(),
                true,
                false,
                1,
                0,
                "Run reached terminal state: completed.",
                metadata);
    }

    @Override
    public AgentRunCancelResult cancelRun(String runId, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("state", AgentRunState.COMPLETED.name());
        metadata.put("strategy", STRATEGY);
        String normalizedReason = reason == null ? "" : reason.trim();
        if (!normalizedReason.isEmpty()) {
            metadata.put("reason", normalizedReason);
        }
        return new AgentRunCancelResult(
                normalizeRunId(runId),
                false,
                AgentRunHandle.completed(RUN_ID, STRATEGY),
                "Contract run is already completed and cannot be cancelled.",
                metadata);
    }

    @Override
    public AgentRunForgetResult forgetRun(String runId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("state", AgentRunState.COMPLETED.name());
        metadata.put("strategy", STRATEGY);
        return new AgentRunForgetResult(
                normalizeRunId(runId),
                true,
                "Forgot Andalus run status.",
                metadata);
    }

    private static AgentRunStatus statusSnapshot() {
        return new AgentRunStatus(
                AgentRunHandle.completed(RUN_ID, STRATEGY),
                true,
                "Contract run completed.",
                lifecycleMetadata());
    }

    private static SurfacePolicyAssessment readySurface() {
        return new SurfacePolicyAssessment(
                "coding-agent",
                true,
                List.of("surfaceId", "workspace", "harness"),
                List.of(),
                List.of(),
                List.of("inspect-workspace", "plan-harness", "prefer-tool-use"));
    }

    private static AgentRunSkillAssessment readySkills() {
        return new AgentRunSkillAssessment(
                "coding-agent",
                true,
                List.of("repo"),
                List.of("repo.context"),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static AgentRunEvent event(String runId) {
        return new AgentRunEvent(
                runId,
                1,
                "run.completed",
                AgentRunState.COMPLETED,
                "Contract run completed.",
                lifecycleMetadata());
    }

    private static String normalizeRunId(String runId) {
        return runId == null || runId.isBlank() ? RUN_ID : runId.trim();
    }

    private static Map<String, Object> lifecycleMetadata() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("tenant", "tenant-contract");
        values.put("session", "session-contract");
        values.put("surface", "assistant-agent");
        return values;
    }

    @Override
    public void setPreferredProvider(String providerId) {
        delegate.setPreferredProvider(providerId);
    }

    @Override
    public java.util.Optional<String> getPreferredProvider() {
        return delegate.getPreferredProvider();
    }

    @Override
    public List<String> listAvailableProviders() {
        return delegate.listAvailableProviders();
    }
}
