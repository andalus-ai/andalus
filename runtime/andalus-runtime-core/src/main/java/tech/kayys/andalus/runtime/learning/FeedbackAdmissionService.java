package tech.kayys.andalus.runtime.learning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import tech.kayys.andalus.harness.learning.FeedbackAggregation;
import tech.kayys.andalus.harness.learning.FeedbackTrustPolicy;
import tech.kayys.andalus.harness.learning.LearningSignal;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Runtime boundary for fail-closed, durable, advisory human-feedback admission. */
@ApplicationScoped
public class FeedbackAdmissionService {
    private static final String STORE_UNAVAILABLE =
            "durable admitted-feedback ledger is unavailable";

    @Inject
    Instance<DataSource> dataSources;

    /** Uses the configured datasource; missing or ambiguous datasource rejects admission. */
    public FeedbackAggregation.AdmissionResult admit(
            LearningSignal signal,
            FeedbackTrustPolicy.Policy trustPolicy,
            Set<String> corroboratingSources) {
        requireInputs(signal, trustPolicy);
        if (dataSources == null || !dataSources.isResolvable()) {
            return rejected();
        }
        try {
            return admit(dataSources.get(), signal, trustPolicy, corroboratingSources);
        } catch (RuntimeException exception) {
            return rejected();
        }
    }

    /** Package-visible seam for deterministic adapter tests. */
    FeedbackAggregation.AdmissionResult admit(
            DataSource dataSource,
            LearningSignal signal,
            FeedbackTrustPolicy.Policy trustPolicy,
            Set<String> corroboratingSources) {
        requireInputs(signal, trustPolicy);
        if (dataSource == null) {
            return rejected();
        }
        try {
            JdbcAdmittedFeedbackLedger ledger = new JdbcAdmittedFeedbackLedger(dataSource);
            ledger.verifySchema();
            return ledger.admit(signal, trustPolicy,
                    corroboratingSources == null ? Set.of() : Set.copyOf(corroboratingSources));
        } catch (IllegalStateException exception) {
            return rejected();
        }
    }

    private static void requireInputs(LearningSignal signal, FeedbackTrustPolicy.Policy trustPolicy) {
        if (signal == null || trustPolicy == null) {
            throw new IllegalArgumentException("signal and trustPolicy are required");
        }
    }

    private static FeedbackAggregation.AdmissionResult rejected() {
        return new FeedbackAggregation.AdmissionResult(
                Optional.empty(), List.of(STORE_UNAVAILABLE));
    }
}
