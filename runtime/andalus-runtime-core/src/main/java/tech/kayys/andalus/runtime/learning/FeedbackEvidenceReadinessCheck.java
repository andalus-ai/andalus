package tech.kayys.andalus.runtime.learning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import javax.sql.DataSource;

/**
 * Readiness indicator for durable feedback evidence reservations.
 *
 * <p>A missing datasource, unavailable database, or unapplied migration keeps
 * the service unready. The check is read-only and never bootstraps the schema
 * or falls back to process-local storage.</p>
 */
@Readiness
@ApplicationScoped
public class FeedbackEvidenceReadinessCheck implements HealthCheck {

    @Inject
    Instance<DataSource> dataSources;

    @Override
    public HealthCheckResponse call() {
        if (dataSources == null || !dataSources.isResolvable()) {
            return unavailable("datasource-not-configured");
        }
        try {
            return check(dataSources.get());
        } catch (RuntimeException exception) {
            return unavailable("datasource-unavailable");
        }
    }

    HealthCheckResponse check(DataSource dataSource) {
        if (dataSource == null) {
            return unavailable("datasource-not-configured");
        }
        try {
            new JdbcAdmittedFeedbackLedger(dataSource).verifySchema();
            return HealthCheckResponse.named("feedback-admitted-ledger")
                    .up()
                    .withData("backend", "jdbc")
                    .withData("schema", "available")
                    .build();
        } catch (RuntimeException exception) {
            // Keep credentials, SQL, hostnames and vendor error details out of health output.
            return unavailable("schema-or-database-unavailable");
        }
    }

    private HealthCheckResponse unavailable(String reason) {
        return HealthCheckResponse.named("feedback-evidence-store")
                .down()
                .withData("backend", "jdbc")
                .withData("reason", reason)
                .build();
    }
}
