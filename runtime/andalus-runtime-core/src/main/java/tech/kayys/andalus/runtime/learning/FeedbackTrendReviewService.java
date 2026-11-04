package tech.kayys.andalus.runtime.learning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Runtime boundary for review decisions and proposal-specific shadow eligibility. */
@ApplicationScoped
public class FeedbackTrendReviewService {
    @Inject
    Instance<DataSource> dataSources;

    public JdbcFeedbackTrendReviewStore.Review record(
            FeedbackTrendReviewPolicy.Assessment assessment, String reviewerId,
            JdbcFeedbackTrendReviewStore.Disposition disposition, String rationale) {
        Objects.requireNonNull(assessment, "assessment");
        if (dataSources == null || !dataSources.isResolvable()) {
            throw new IllegalStateException("A unique durable datasource is required for review recording");
        }
        return new JdbcFeedbackTrendReviewStore(dataSources.get())
                .append(assessment, reviewerId, disposition, rationale);
    }

    public List<JdbcFeedbackTrendReviewStore.Review> history(
            FeedbackAggregationRecoveryService.Subject subject) {
        Objects.requireNonNull(subject, "subject");
        if (dataSources == null || !dataSources.isResolvable()) return List.of();
        return new JdbcFeedbackTrendReviewStore(dataSources.get()).findBySubject(subject);
    }

    /** Records explicit approval/rejection of one exact proposal; reviewer identity must be authenticated upstream. */
    public JdbcFeedbackAdaptationApprovalStore.Approval decideProposal(
            FeedbackAdaptationGate.Proposal proposal, String reviewId, String authenticatedReviewerId,
            JdbcFeedbackAdaptationApprovalStore.Decision decision, String rationale) {
        Objects.requireNonNull(proposal, "proposal");
        if (dataSources == null || !dataSources.isResolvable()) {
            throw new IllegalStateException("A unique durable datasource is required for proposal decisions");
        }
        return new JdbcFeedbackAdaptationApprovalStore(dataSources.get())
                .append(proposal, reviewId, authenticatedReviewerId, decision, rationale);
    }

    /** Only an exact persisted APPROVED proposal decision can open this shadow-only gate. */
    public FeedbackAdaptationGate.Result evaluateForShadow(
            FeedbackAdaptationGate.Proposal proposal, String authenticatedReviewerId) {
        Objects.requireNonNull(proposal, "proposal");
        if (dataSources == null || !dataSources.isResolvable()) {
            return FeedbackAdaptationGate.evaluate(proposal, Optional.empty(), authenticatedReviewerId);
        }
        List<JdbcFeedbackAdaptationApprovalStore.Approval> approvals =
                new JdbcFeedbackAdaptationApprovalStore(dataSources.get()).findByProposal(proposal);
        Optional<JdbcFeedbackAdaptationApprovalStore.Approval> approval = approvals.stream()
                .filter(candidate -> candidate.sourceSnapshotId().equals(proposal.sourceSnapshotId())
                        && candidate.policyFingerprint().equals(proposal.policyFingerprint()))
                .reduce((first, second) -> second);
        return FeedbackAdaptationGate.evaluate(proposal, approval, authenticatedReviewerId);
    }
}
