package tech.kayys.andalus.runtime.learning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Runtime boundary for recording reviews and checking shadow-evaluation eligibility. */
@ApplicationScoped
public class FeedbackTrendReviewService {
    @Inject
    Instance<DataSource> dataSources;

    public JdbcFeedbackTrendReviewStore.Review record(
            FeedbackTrendReviewPolicy.Assessment assessment,
            String reviewerId,
            JdbcFeedbackTrendReviewStore.Disposition disposition,
            String rationale) {
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
        if (dataSources == null || !dataSources.isResolvable()) {
            return List.of();
        }
        return new JdbcFeedbackTrendReviewStore(dataSources.get()).findBySubject(subject);
    }

    /**
     * Checks a persisted accepted review before a proposal may enter shadow evaluation.
     * authenticatedReviewerId must come from the caller's authenticated security principal.
     */
    public FeedbackAdaptationGate.Result evaluateForShadow(
            FeedbackAdaptationGate.Proposal proposal,
            String reviewId,
            String authenticatedReviewerId) {
        Objects.requireNonNull(proposal, "proposal");
        if (dataSources == null || !dataSources.isResolvable()) {
            return FeedbackAdaptationGate.evaluate(proposal, Optional.empty(), authenticatedReviewerId);
        }
        List<JdbcFeedbackTrendReviewStore.Review> reviews =
                new JdbcFeedbackTrendReviewStore(dataSources.get()).findBySubject(proposal.subject());
        Optional<JdbcFeedbackTrendReviewStore.Review> review = reviews.stream()
                .filter(candidate -> candidate.id().equals(reviewId))
                .findFirst();
        return FeedbackAdaptationGate.evaluate(proposal, review, authenticatedReviewerId);
    }
}
