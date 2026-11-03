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
 * This service never activates candidate behavior or changes production routing.
 */
@ApplicationScoped
public class ShadowEvaluationService {
    @Inject
    Instance<DataSource> dataSources;

    @Inject
    FeedbackTrendReviewService reviewService;

    @Inject
    Instance<ShadowEvaluationTrustRegistry> trustRegistries;

    @Inject
    Instance<ShadowEvaluationManifestVerifier> manifestVerifiers;

    /** Fails closed unless the authenticated reviewer still matches the exact proposal approval. */
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
        if (dataSources == null || !dataSources.isResolvable()) {
            throw new IllegalStateException("a unique durable datasource is required for shadow evidence");
        }
        return new JdbcShadowEvaluationStore(dataSources.get()).append(report);
    }

    /**
     * Checks approval before any evaluator is invoked, runs both sides offline on the same dataset,
     * then re-checks approval before durable recording.
     */
    public CompletionStage<ShadowEvaluationReport> evaluateAndRecord(
            ShadowEvaluationRunner.RunRequest request, String authenticatedReviewerId) {
        Objects.requireNonNull(request, "request");
        FeedbackAdaptationGate.Result gate = reviewService.evaluateForShadow(
                request.proposal(), authenticatedReviewerId);
        if (!gate.eligibleForShadowEvaluation()
                || !request.approvalId().equals(gate.approvalId())
                || !request.proposal().sourceSnapshotId().equals(gate.sourceSnapshotId())) {
            throw new IllegalStateException("shadow evaluation is not authorized by the current proposal approval");
        }
        verifyTrustedInputs(request);
        return new ShadowEvaluationRunner().evaluate(request)
                .thenApply(report -> record(report, authenticatedReviewerId));
    }

    public List<ShadowEvaluationReport> history(FeedbackAdaptationGate.Proposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        if (dataSources == null || !dataSources.isResolvable()) return List.of();
        return new JdbcShadowEvaluationStore(dataSources.get()).findByProposal(proposal);
    }
}
