package tech.kayys.andalus.runtime.learning;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.eclipse.microprofile.health.HealthCheckResponse;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackEvidenceReadinessCheckTest {

    @Test
    void reportsUpOnlyWhenMigrationHasCreatedTheRequiredTable() throws Exception {
        JdbcDataSource dataSource = dataSource();
        applyMigration(dataSource);

        HealthCheckResponse response = new FeedbackEvidenceReadinessCheck().check(dataSource);

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
    }

    @Test
    void reportsDownWhenMigrationHasNotBeenApplied() throws Exception {
        HealthCheckResponse response = new FeedbackEvidenceReadinessCheck().check(dataSource());

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(response.getData()).containsKey("reason");
    }

    @Test
    void reportsDownWhenDatasourceIsMissing() {
        HealthCheckResponse response = new FeedbackEvidenceReadinessCheck().check(null);

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
    }

    @Test
    void reportsDownWhenDatabaseCannotBeReached() {
        JdbcDataSource unavailable = new JdbcDataSource();
        unavailable.setURL("jdbc:driver-that-does-not-exist:feedback");

        HealthCheckResponse response = new FeedbackEvidenceReadinessCheck().check(unavailable);

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
    }

    private JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:readiness_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
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
