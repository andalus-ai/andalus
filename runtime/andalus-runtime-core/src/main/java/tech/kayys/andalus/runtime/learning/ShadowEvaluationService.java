package tech.kayys.andalus.runtime.learning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Gates paired offline evaluator execution and records its evidence after re-checking the exact persisted approval.
 * Candidate execution remains disabled until the isolated execution SPI is fully wired to byte-only artifact
 * verification and an OS-enforced worker. The legacy RunRequest carries host-loaded Evaluator objects and is
 * therefore deliberately not executed by this service.
 */
@ApplicationScoped
public class ShadowEvaluationService {
    @Inject Instance<DataSource> dataSources;
    @Inject FeedbackTrendReviewService reviewService;
    @Inject Instance<ShadowEvaluationManifestVerifier> manifestVerifiers;
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
     * Legacy compatibility entry point. It fails closed because RunRequest contains evaluator
     * instances that may already have been loaded in the application process. Use the isolated
     * manifest-based execution API once a deployment worker adapter is installed.
     */
    public CompletionStage<ShadowEvaluationReport> evaluateAndRecord(
            ShadowEvaluationRunner.RunRequest request, String authenticatedReviewerId) {
        Objects.requireNonNull(request, "request");
        throw new IllegalStateException(
                "host-process shadow evaluator execution is disabled; an OS-isolated worker is required");
    }

    /** No reports are exposed when durable storage is unavailable or ambiguous. */
    public List<ShadowEvaluationReport> history(FeedbackAdaptationGate.Proposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        if (dataSources == null || !dataSources.isResolvable() || dataSources.isAmbiguous()) return List.of();
        return new JdbcShadowEvaluationStore(dataSources.get()).findByProposal(proposal);
    }
}
