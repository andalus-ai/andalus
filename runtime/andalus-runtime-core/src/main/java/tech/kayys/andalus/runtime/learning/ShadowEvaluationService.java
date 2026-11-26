package tech.kayys.andalus.runtime.learning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Executes paired offline evaluations only through an OS-isolated worker and records
 * evidence only after revalidating the exact proposal-specific approval.
 */
@ApplicationScoped
public class ShadowEvaluationService {
    @Inject Instance<DataSource> dataSources;
    @Inject FeedbackTrendReviewService reviewService;
    @Inject Instance<ShadowEvaluationExecutionBoundary> executionBoundaries;

    ShadowEvaluationReport record(ShadowEvaluationReport report, String authenticatedReviewerId) {
        Objects.requireNonNull(report, "report");
        FeedbackAdaptationGate.Proposal proposal = new FeedbackAdaptationGate.Proposal(
                report.proposalId(), report.subject(), report.sourceSnapshotId(), report.policyFingerprint());
        FeedbackAdaptationGate.Result gate = reviewService.evaluateForShadow(proposal, authenticatedReviewerId);
        if (!gate.eligibleForShadowEvaluation()
                || !report.approvalId().equals(gate.approvalId())
                || !report.sourceSnapshotId().equals(gate.sourceSnapshotId())) {
            throw new IllegalStateException("shadow evaluation is not authorized by the current proposal approval");
        }
        if (dataSources == null || !dataSources.isResolvable() || dataSources.isAmbiguous()) {
            throw new IllegalStateException("a unique durable datasource is required for shadow evidence");
        }
        return new JdbcShadowEvaluationStore(dataSources.get()).append(report);
    }

    /**
     * Production execution entry point. Authorization is checked before spending worker
     * resources, and the report is checked against the exact request before durable append.
     */
    public CompletionStage<ShadowEvaluationReport> evaluateAndRecord(
            ShadowEvaluationExecutionBoundary.ExecutionRequest request, String authenticatedReviewerId) {
        Objects.requireNonNull(request, "request");
        if (authenticatedReviewerId == null || authenticatedReviewerId.isBlank()) {
            throw new SecurityException("authenticated reviewer identity is required");
        }

        FeedbackAdaptationGate.Result approval =
                reviewService.evaluateForShadow(request.proposal(), authenticatedReviewerId);
        if (!approval.eligibleForShadowEvaluation()
                || !request.approvalId().equals(approval.approvalId())
                || !request.proposal().sourceSnapshotId().equals(approval.sourceSnapshotId())) {
            throw new SecurityException("shadow execution is not authorized by the current proposal approval");
        }

        if (dataSources == null || !dataSources.isResolvable() || dataSources.isAmbiguous()) {
            throw new IllegalStateException("a unique durable datasource is required before shadow execution");
        }
        if (executionBoundaries == null || !executionBoundaries.isResolvable()
                || executionBoundaries.isAmbiguous()) {
            throw new IllegalStateException("a unique isolated shadow execution boundary is required");
        }
        ShadowEvaluationExecutionBoundary boundary = executionBoundaries.get();
        ShadowEvaluationExecutionBoundary.IsolationLevel isolation = boundary.isolationLevel();
        if (isolation != ShadowEvaluationExecutionBoundary.IsolationLevel.CONTAINER
                && isolation != ShadowEvaluationExecutionBoundary.IsolationLevel.MICRO_VM) {
            throw new IllegalStateException("shadow execution boundary does not provide OS-enforced isolation");
        }

        return boundary.execute(request).thenApply(report -> {
            validateReportBinding(request, report);
            return record(report, authenticatedReviewerId);
        });
    }

    /**
     * Legacy compatibility entry point. RunRequest contains host-loaded Evaluator instances,
     * so accepting it here would bypass the byte-only worker trust boundary.
     */
    public CompletionStage<ShadowEvaluationReport> evaluateAndRecord(
            ShadowEvaluationRunner.RunRequest request, String authenticatedReviewerId) {
        Objects.requireNonNull(request, "request");
        throw new IllegalStateException(
                "host-process shadow evaluator execution is disabled; use the manifest-based isolated execution API");
    }

    private static void validateReportBinding(
            ShadowEvaluationExecutionBoundary.ExecutionRequest request, ShadowEvaluationReport report) {
        Objects.requireNonNull(report, "isolated worker returned no report");
        if (!report.runId().equals(request.runId())
                || !report.proposalId().equals(request.proposal().id())
                || !report.subject().equals(request.proposal().subject())
                || !report.sourceSnapshotId().equals(request.proposal().sourceSnapshotId())
                || !report.policyFingerprint().equals(request.proposal().policyFingerprint())
                || !report.approvalId().equals(request.approvalId())
                || !report.baselineCandidateId().equals(request.baselineCandidateId())
                || !report.baselineCandidateVersion().equals(request.baselineCandidateVersion())
                || !report.candidateId().equals(request.candidateId())
                || !report.candidateVersion().equals(request.candidateVersion())
                || !report.baselineEvaluatorId().equals(request.baselineDescriptor().id())
                || !report.baselineEvaluatorVersion().equals(request.baselineDescriptor().version())
                || !report.baselineEvaluatorConfigFingerprint().equals(
                        request.baselineDescriptor().configurationFingerprint())
                || !report.candidateEvaluatorId().equals(request.candidateDescriptor().id())
                || !report.candidateEvaluatorVersion().equals(request.candidateDescriptor().version())
                || !report.candidateEvaluatorConfigFingerprint().equals(
                        request.candidateDescriptor().configurationFingerprint())
                || !report.datasetId().equals(request.dataset().id())
                || !report.datasetVersion().equals(request.dataset().version())
                || !report.datasetFingerprint().equals(ShadowEvaluationRunner.fingerprint(request.dataset()))) {
            throw new IllegalStateException("isolated worker report does not match the authorized execution request");
        }
    }

    /** No reports are exposed when durable storage is unavailable or ambiguous. */
    public List<ShadowEvaluationReport> history(FeedbackAdaptationGate.Proposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        if (dataSources == null || !dataSources.isResolvable() || dataSources.isAmbiguous()) return List.of();
        return new JdbcShadowEvaluationStore(dataSources.get()).findByProposal(proposal);
    }
}
