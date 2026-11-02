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
            String aggregationPolicyFingerprint,
            String corroborationFingerprint,
            List<String> consideredSignalIds,
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
            if (aggregationPolicyFingerprint == null || aggregationPolicyFingerprint.isBlank()
                    || corroborationFingerprint == null || corroborationFingerprint.isBlank()) {
                throw new IllegalArgumentException("all policy fingerprints are required");
            }
            consideredSignalIds = List.copyOf(consideredSignalIds);
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

    /** Aggregates and appends an immutable history entry; it never changes production policy. */
    public Optional<JdbcFeedbackAggregationSnapshotStore.Snapshot> aggregateAndSnapshot(
            Subject subject,
            FeedbackAggregation.Policy aggregationPolicy,
            FeedbackTrustPolicy.Policy trustPolicy,
            Map<String, Set<String>> corroboratingSourcesBySignal) {
        Objects.requireNonNull(subject, "subject");
        if (dataSources == null || !dataSources.isResolvable()) {
            return Optional.empty();
        }
        return Optional.of(aggregateAndSnapshot(dataSources.get(), subject, aggregationPolicy,
                trustPolicy, corroboratingSourcesBySignal));
    }

    JdbcFeedbackAggregationSnapshotStore.Snapshot aggregateAndSnapshot(
            DataSource dataSource,
            Subject subject,
            FeedbackAggregation.Policy aggregationPolicy,
            FeedbackTrustPolicy.Policy trustPolicy,
            Map<String, Set<String>> corroboratingSourcesBySignal) {
        AdvisoryReport report = aggregate(dataSource, subject, aggregationPolicy,
                trustPolicy, corroboratingSourcesBySignal);
        return new JdbcFeedbackAggregationSnapshotStore(dataSource).save(report);
    }

    /** Reads immutable advisory history for the exact typed subject. */
    public List<JdbcFeedbackAggregationSnapshotStore.Snapshot> history(
            Subject subject) {
        Objects.requireNonNull(subject, "subject");
        if (dataSources == null || !dataSources.isResolvable()) {
            return List.of();
        }
        JdbcFeedbackAggregationSnapshotStore store =
                new JdbcFeedbackAggregationSnapshotStore(dataSources.get());
        store.verifySchema();
        return store.findBySubject(subject);
    }

    /**
     * Compares the two latest persisted snapshots for a subject.
     * Returns empty when the durable datasource is not uniquely configured or no history exists.
     */
    public Optional<FeedbackTrendAnalyzer.Trend> analyzeTrend(Subject subject) {
        Objects.requireNonNull(subject, "subject");
        List<JdbcFeedbackAggregationSnapshotStore.Snapshot> snapshots = history(subject);
        if (snapshots.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(FeedbackTrendAnalyzer.analyze(snapshots));
    }

    /** Same as {@link #analyzeTrend(Subject)} with an explicit positive-ratio regression threshold. */
    public Optional<FeedbackTrendAnalyzer.Trend> analyzeTrend(
            Subject subject, double minimumPositiveRatioDrop) {
        Objects.requireNonNull(subject, "subject");
        List<JdbcFeedbackAggregationSnapshotStore.Snapshot> snapshots = history(subject);
        if (snapshots.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(FeedbackTrendAnalyzer.analyze(snapshots, minimumPositiveRatioDrop));
    }

    /**
     * Produces a human-review recommendation from persisted history.
     * The assessment is advisory and never dispatches or applies a change.
     */
    public Optional<FeedbackTrendReviewPolicy.Assessment> reviewTrend(
            Subject subject, FeedbackTrendReviewPolicy.Policy reviewPolicy) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(reviewPolicy, "reviewPolicy");
        List<JdbcFeedbackAggregationSnapshotStore.Snapshot> snapshots = history(subject);
        if (snapshots.isEmpty()) {
            return Optional.empty();
        }
        FeedbackTrendAnalyzer.Trend trend = FeedbackTrendAnalyzer.analyze(snapshots);
        JdbcFeedbackAggregationSnapshotStore.Snapshot current = snapshots.stream()
                .filter(snapshot -> snapshot.id().equals(trend.currentSnapshotId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("current trend snapshot is missing"));
        return Optional.of(FeedbackTrendReviewPolicy.evaluate(trend, reviewPolicy, current));
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
        Set<String> consideredSignalIds = new TreeSet<>();
        Set<String> signalIds = new TreeSet<>();
        Set<String> sourceIds = new TreeSet<>();
        Set<String> evidenceReferences = new TreeSet<>();
        Map<String, Set<String>> corroboration = corroboratingSourcesBySignal == null
                ? Map.of() : Map.copyOf(corroboratingSourcesBySignal);

        for (LearningSignal signal : ledger.findAll()) {
            if (!subject.matches(signal)) {
                continue;
            }
            consideredSignalIds.add(signal.id());
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
                fingerprint(aggregationPolicy), fingerprint(corroboration),
                List.copyOf(consideredSignalIds), List.copyOf(signalIds),
                List.copyOf(sourceIds), List.copyOf(evidenceReferences));
    }


    static String fingerprint(FeedbackAggregation.Policy policy) {
        String canonical = policy.minimumDistinctSources() + "|"
                + policy.minimumEvidenceReferences() + "|"
                + Double.toHexString(policy.consensusRatio());
        return sha256(canonical);
    }

    static String fingerprint(Map<String, Set<String>> corroboration) {
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(corroboration).forEach((signalId, sources) -> {
            canonical.append(signalId.length()).append(':').append(signalId).append('=');
            new TreeSet<>(sources).forEach(source ->
                    canonical.append(source.length()).append(':').append(source).append(';'));
            canonical.append('|');
        });
        return sha256(canonical.toString());
    }

    private static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static String fingerprint(FeedbackTrustPolicy.Policy policy) {
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(policy.sourceTrust()).forEach((source, weight) ->
                canonical.append(source.length()).append(':').append(source)
                        .append('=').append(Double.toHexString(weight)).append(';'));
        canonical.append('|').append(Double.toHexString(policy.minimumSourceTrust()))
                .append('|').append(policy.minimumEvidenceReferences())
                .append('|').append(policy.minimumDistinctTrustedSources());
        return sha256(canonical.toString());
    }
}
