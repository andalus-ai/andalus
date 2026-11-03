package tech.kayys.andalus.runtime.learning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import javax.sql.DataSource;

/** Readiness for the durable shadow trust registry and its mandatory cryptographic verifier. */
@Readiness
@ApplicationScoped
public class ShadowEvaluationTrustReadinessCheck implements HealthCheck {
    @Inject
    Instance<DataSource> dataSources;

    @Inject
    Instance<ShadowEvaluationManifestVerifier> verifiers;

    @Override
    public HealthCheckResponse call() {
        if (dataSources == null || !dataSources.isResolvable())
            return down("datasource-not-configured");
        if (verifiers == null || !verifiers.isResolvable())
            return down("manifest-verifier-not-configured");
        try {
            new JdbcShadowEvaluationTrustRegistry(dataSources.get()).verifySchema();
            return HealthCheckResponse.named("shadow-evaluation-trust")
                    .up().withData("registry", "jdbc")
                    .withData("verifier", "configured").build();
        } catch (RuntimeException exception) {
            return down("schema-or-database-unavailable");
        }
    }

    private HealthCheckResponse down(String reason) {
        return HealthCheckResponse.named("shadow-evaluation-trust")
                .down().withData("reason", reason).build();
    }
}
