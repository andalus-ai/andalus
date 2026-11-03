package tech.kayys.andalus.runtime.learning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Records externally-produced shadow evidence only after re-checking the exact persisted approval.
 * This service does not execute evaluators or activate candidate behavior.
 */
@ApplicationScoped
public class ShadowEvaluationService {
    @Inject
    Instance<DataSource> dataSources;

    @Inject
    FeedbackTrendReviewService reviewService;

    /** Fails closed unless the authenticated reviewer still matches the exact proposal approval. */
    public ShadowEvaluationReport record(ShadowEvaluationReport report, String authenticatedReviewerId) {
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
        return new ShadowEvaluationRunner().evaluate(request)
                .thenApply(report -> record(report, authenticatedReviewerId));
    }

    public List<ShadowEvaluationReport> history(FeedbackAdaptationGate.Proposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        if (dataSources == null || !dataSources.isResolvable()) return List.of();
        return new JdbcShadowEvaluationStore(dataSources.get()).findByProposal(proposal);
    }
}
