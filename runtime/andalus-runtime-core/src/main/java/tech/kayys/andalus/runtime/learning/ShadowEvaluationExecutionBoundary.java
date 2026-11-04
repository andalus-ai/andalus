package tech.kayys.andalus.runtime.learning;

import tech.kayys.andalus.harness.evaluation.Evaluator;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Deployment-provided boundary for executing signed evaluator artifacts outside the application process.
 *
 * Implementations MUST load evaluator code only inside an OS-enforced container or microVM,
 * with a read-only artifact mount, bounded resources, no inherited application credentials,
 * restricted network access, bounded protocol frames, and process-tree termination on timeout.
 * A plain Java classloader, thread, or subprocess without OS isolation is not sufficient.
 *
 * This SPI deliberately accepts manifests and immutable evaluation data, never an Evaluator
 * instance. The worker must independently verify the artifact digest before loading it.
 */
public interface ShadowEvaluationExecutionBoundary {
    enum IsolationLevel { NONE, PROCESS_ONLY, CONTAINER, MICRO_VM }

    record ExecutionRequest(
            String runId,
            FeedbackAdaptationGate.Proposal proposal,
            String approvalId,
            String baselineCandidateId,
            String baselineCandidateVersion,
            Evaluator.EvaluatorDescriptor baselineDescriptor,
            ShadowEvaluationManifest.EvaluatorArtifactManifest baselineArtifact,
            String candidateId,
            String candidateVersion,
            Evaluator.EvaluatorDescriptor candidateDescriptor,
            ShadowEvaluationManifest.EvaluatorArtifactManifest candidateArtifact,
            Evaluator.Dataset dataset,
            List<ShadowEvaluationRunner.MetricRule> metricRules) {
        public ExecutionRequest {
            if (runId == null || runId.isBlank() || proposal == null
                    || approvalId == null || approvalId.isBlank()
                    || baselineDescriptor == null || baselineArtifact == null
                    || candidateDescriptor == null || candidateArtifact == null
                    || dataset == null || metricRules == null || metricRules.isEmpty()) {
                throw new IllegalArgumentException("incomplete isolated shadow execution request");
            }
            metricRules = List.copyOf(metricRules);
        }
    }

    /** Declares the actual isolation supplied by the deployment adapter. */
    IsolationLevel isolationLevel();

    /** Executes both evaluators against the same immutable dataset revision. */
    CompletionStage<ShadowEvaluationReport> execute(ExecutionRequest request);
}
