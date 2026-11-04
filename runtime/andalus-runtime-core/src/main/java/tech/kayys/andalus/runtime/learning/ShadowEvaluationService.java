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
        if (dataSources == null || !dataSources.isResolvable() || dataSources.isAmbiguous()) {
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

    private void verifyTrustedInputs(ShadowEvaluationRunner.RunRequest request) {
        if (dataSources == null || !dataSources.isResolvable() || dataSources.isAmbiguous()
                || manifestVerifiers == null || !manifestVerifiers.isResolvable() || manifestVerifiers.isAmbiguous()) {
            throw new IllegalStateException("a unique durable datasource and manifest verifier are required");
        }
        ShadowEvaluationTrustRegistry registry = new JdbcShadowEvaluationTrustRegistry(dataSources.get());
        ShadowEvaluationManifestVerifier verifier = manifestVerifiers.get();
        var dataset = request.dataset();
        var manifest = registry.findDataset(dataset.id(), dataset.version())
                .orElseThrow(() -> new IllegalStateException("trusted dataset manifest not found"));
        String fingerprint = ShadowEvaluationRunner.fingerprint(dataset);
        if (!manifest.datasetId().equals(dataset.id())
                || !manifest.datasetVersion().equals(dataset.version())
                || manifest.caseCount() != dataset.cases().size()
                || !manifest.contentFingerprint().equals(fingerprint)
                || !verifier.verifyDataset(manifest, dataset)) {
            throw new IllegalStateException("dataset manifest verification failed");
        }
        verifyEvaluatorArtifact(registry, verifier, request.baselineCandidateId(),
                request.baselineCandidateVersion(), request.baselineDescriptor(), request.evaluatorForBaseline());
        verifyEvaluatorArtifact(registry, verifier, request.candidateId(),
                request.candidateVersion(), request.candidateDescriptor(), request.evaluatorForCandidate());
    }

    private static void verifyEvaluatorArtifact(
            ShadowEvaluationTrustRegistry registry,
            ShadowEvaluationManifestVerifier verifier,
            String candidateId, String candidateVersion,
            tech.kayys.andalus.harness.evaluation.Evaluator.EvaluatorDescriptor descriptor,
            tech.kayys.andalus.harness.evaluation.Evaluator evaluator) {
        String fingerprint = descriptor.configurationFingerprint();
        var manifest = registry.findEvaluatorArtifact(candidateId, candidateVersion,
                        descriptor.id(), descriptor.version(), fingerprint)
                .orElseThrow(() -> new IllegalStateException("trusted evaluator artifact manifest not found"));
        if (!manifest.candidateId().equals(candidateId)
                || !manifest.candidateVersion().equals(candidateVersion)
                || !manifest.evaluatorId().equals(descriptor.id())
                || !manifest.evaluatorVersion().equals(descriptor.version())
                || !manifest.configurationFingerprint().equals(fingerprint)
                || !verifier.verifyEvaluatorArtifact(manifest, evaluator)) {
            throw new IllegalStateException("evaluator artifact verification failed");
        }
    }

    public List<ShadowEvaluationReport> history(FeedbackAdaptationGate.Proposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        if (dataSources == null || !dataSources.isResolvable() || dataSources.isAmbiguous()) return List.of();
        return new JdbcShadowEvaluationStore(dataSources.get()).findByProposal(proposal);
    }
}
