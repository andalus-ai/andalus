package tech.kayys.andalus.runtime.learning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;

/** Runtime boundary for recording and recovering explicit human review dispositions. */
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
}
