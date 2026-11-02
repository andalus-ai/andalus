package tech.kayys.andalus.runtime.learning;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.harness.learning.FeedbackAggregation;
import tech.kayys.andalus.harness.learning.FeedbackTrustPolicy;
import tech.kayys.andalus.harness.learning.LearningSignal;
import tech.kayys.andalus.harness.learning.LearningSignalPolarity;
import tech.kayys.andalus.harness.learning.LearningSignalType;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackAdmissionServiceTest {

    private final FeedbackAdmissionService service = new FeedbackAdmissionService();

    @Test
    void admitsTrustedHumanFeedbackAfterSchemaReadinessAndReservation() throws Exception {
        JdbcDataSource dataSource = dataSource();
        applyMigration(dataSource);

        FeedbackAggregation.AdmissionResult result = service.admit(
                dataSource, signal("signal-a", LearningSignalType.HUMAN_FEEDBACK, "evidence-a"),
                trustPolicy(), Set.of());

        assertThat(result.accepted()).isTrue();
        assertThat(new JdbcFeedbackEvidenceStore(dataSource).findOwner("evidence-a"))
                .contains("signal-a");
    }

    @Test
    void rejectsAdmissionWhenSchemaIsMissingWithoutCreatingIt() {
        JdbcDataSource dataSource = dataSource();

        FeedbackAggregation.AdmissionResult result = service.admit(
                dataSource, signal("signal-a", LearningSignalType.HUMAN_FEEDBACK, "evidence-a"),
                trustPolicy(), Set.of());

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasons()).containsExactly("durable feedback evidence store is unavailable");
    }

    @Test
    void rejectsAdmissionWhenDatabaseIsUnavailable() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:driver-that-does-not-exist:feedback");

        FeedbackAggregation.AdmissionResult result = service.admit(
                dataSource, signal("signal-a", LearningSignalType.HUMAN_FEEDBACK, "evidence-a"),
                trustPolicy(), Set.of());

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasons()).containsExactly("durable feedback evidence store is unavailable");
    }

    @Test
    void rejectsNonHumanSignalsWithoutConsumingEvidence() throws Exception {
        JdbcDataSource dataSource = dataSource();
        applyMigration(dataSource);

        FeedbackAggregation.AdmissionResult result = service.admit(
                dataSource, signal("signal-a", LearningSignalType.EVALUATION, "evidence-a"),
                trustPolicy(), Set.of());

        assertThat(result.accepted()).isFalse();
        assertThat(new JdbcFeedbackEvidenceStore(dataSource).findOwner("evidence-a")).isEmpty();
    }

    @Test
    void rejectsReplayWithoutChangingTheOriginalReservation() throws Exception {
        JdbcDataSource dataSource = dataSource();
        applyMigration(dataSource);

        FeedbackAggregation.AdmissionResult first = service.admit(
                dataSource, signal("signal-a", LearningSignalType.HUMAN_FEEDBACK, "evidence-a"),
                trustPolicy(), Set.of());
        FeedbackAggregation.AdmissionResult replay = service.admit(
                dataSource, signal("signal-b", LearningSignalType.HUMAN_FEEDBACK, "evidence-a"),
                trustPolicy(), Set.of());

        assertThat(first.accepted()).isTrue();
        assertThat(replay.accepted()).isFalse();
        assertThat(new JdbcFeedbackEvidenceStore(dataSource).findOwner("evidence-a"))
                .contains("signal-a");
    }

    private LearningSignal signal(String id, LearningSignalType type, String evidence) {
        return new LearningSignal(id, type, "reviewer", "execution-1",
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                LearningSignalPolarity.POSITIVE, List.of(evidence), java.util.Optional.empty(),
                java.time.Instant.parse("2026-11-02T00:00:00Z"), Map.of());
    }

    private FeedbackTrustPolicy.Policy trustPolicy() {
        return new FeedbackTrustPolicy.Policy(Map.of("reviewer", 1.0), 0.8, 1, 1);
    }

    private JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:admission_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void applyMigration(DataSource dataSource) throws Exception {
        String resource = "/db/migration/V20261102_01__feedback_evidence_reservations.sql";
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }
}
