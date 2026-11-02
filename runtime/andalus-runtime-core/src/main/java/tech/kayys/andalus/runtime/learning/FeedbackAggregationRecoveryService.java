package tech.kayys.andalus.runtime.learning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import tech.kayys.andalus.harness.learning.FeedbackAggregation;
import tech.kayys.andalus.harness.learning.FeedbackTrustPolicy;
import tech.kayys.andalus.harness.learning.LearningSignal;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Rebuilds deterministic advisory aggregates from the durable admitted-feedback ledger.
 * Subject selection is explicit and typed; arbitrary feedback text is never used as a key.
 */
@ApplicationScoped
public class FeedbackAggregationRecoveryService {

    public enum SubjectKind {
        TASK,
        GOAL,
        EVALUATION
    }

    public record Subject(SubjectKind kind, String id) {
        public Subject {
            if (kind == null || id == null || id.isBlank()) {
                throw new IllegalArgumentException("subject kind and non-blank id are required");
            }
        }

        public String key() {
            return kind.name() + ":" + id;
        }

        boolean matches(LearningSignal signal) {
            return switch (kind) {
                case TASK -> signal.taskId().filter(id::equals).isPresent();
                case GOAL -> signal.goalId().filter(id::equals).isPresent();
                case EVALUATION -> signal.evaluationReference().filter(id::equals).isPresent();
            };
        }
    }

    /** Stable provenance for reproducing the advisory result from the same ledger and policies. */
    public record AdvisoryReport(
            Subject subject,
            FeedbackAggregation.Result aggregation,
            String trustPolicyFingerprint,
            List<String> signalIds,
            List<String> sourceIds,
            List<String> evidenceReferences
    ) {
        public AdvisoryReport {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(aggregation, "aggregation");
            if (trustPolicyFingerprint == null || trustPolicyFingerprint.isBlank()) {
                throw new IllegalArgumentException("trust policy fingerprint is required");
            }
            signalIds = List.copyOf(signalIds);
            sourceIds = List.copyOf(sourceIds);
            evidenceReferences = List.copyOf(evidenceReferences);
        }
    }

    @Inject
    Instance<DataSource> dataSources;

    /** Returns empty when no unambiguous durable datasource is configured. */
    public Optional<AdvisoryReport> aggregate(
            Subject subject,
            FeedbackAggregation.Policy aggregationPolicy,
            FeedbackTrustPolicy.Policy trustPolicy,
            Map<String, Set<String>> corroboratingSourcesBySignal) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(aggregationPolicy, "aggregationPolicy");
        Objects.requireNonNull(trustPolicy, "trustPolicy");
        if (dataSources == null || !dataSources.isResolvable()) {
            return Optional.empty();
        }
        return Optional.of(aggregate(dataSources.get(), subject, aggregationPolicy,
                trustPolicy, corroboratingSourcesBySignal));
    }

    /** Package-visible seam for tests and explicit datasource orchestration. */
    AdvisoryReport aggregate(
            DataSource dataSource,
            Subject subject,
            FeedbackAggregation.Policy aggregationPolicy,
            FeedbackTrustPolicy.Policy trustPolicy,
            Map<String, Set<String>> corroboratingSourcesBySignal) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(aggregationPolicy, "aggregationPolicy");
        Objects.requireNonNull(trustPolicy, "trustPolicy");

        JdbcAdmittedFeedbackLedger ledger = new JdbcAdmittedFeedbackLedger(dataSource);
        ledger.verifySchema();

        List<FeedbackAggregation.AdmittedFeedback> admitted = new ArrayList<>();
        Set<String> signalIds = new TreeSet<>();
        Set<String> sourceIds = new TreeSet<>();
        Set<String> evidenceReferences = new TreeSet<>();
        Map<String, Set<String>> corroboration = corroboratingSourcesBySignal == null
                ? Map.of() : Map.copyOf(corroboratingSourcesBySignal);

        for (LearningSignal signal : ledger.findAll()) {
            if (!subject.matches(signal)) {
                continue;
            }
            Optional<FeedbackAggregation.AdmittedFeedback> trusted = FeedbackAggregation.admit(
                    signal, trustPolicy, Set.of(), corroboration.getOrDefault(signal.id(), Set.of()));
            if (trusted.isEmpty()) {
                continue;
            }
            admitted.add(trusted.get());
            signalIds.add(signal.id());
            sourceIds.add(signal.source());
            evidenceReferences.addAll(signal.evidenceReferences());
        }

        FeedbackAggregation.Result result = FeedbackAggregation.aggregate(
                subject.key(), admitted, aggregationPolicy);
        return new AdvisoryReport(subject, result, fingerprint(trustPolicy),
                List.copyOf(signalIds), List.copyOf(sourceIds), List.copyOf(evidenceReferences));
    }

    static String fingerprint(FeedbackTrustPolicy.Policy policy) {
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(policy.sourceTrust()).forEach((source, weight) ->
                canonical.append(source.length()).append(':').append(source)
                        .append('=').append(Double.toHexString(weight)).append(';'));
        canonical.append('|').append(Double.toHexString(policy.minimumSourceTrust()))
                .append('|').append(policy.minimumEvidenceReferences())
                .append('|').append(policy.minimumDistinctTrustedSources());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
