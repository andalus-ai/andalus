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

    @Inject
    Instance<ShadowEvaluationPublicKeyResolver> keyResolvers;

    @Inject
    Instance<ShadowEvaluationArtifactResolver> artifactResolvers;

    @Override
    public HealthCheckResponse call() {
        if (dataSources == null || !dataSources.isResolvable())
            return down("datasource-not-configured");
        if (verifiers == null || !verifiers.isResolvable() || verifiers.isAmbiguous())
            return down("manifest-verifier-not-configured-or-ambiguous");
        if (keyResolvers == null || !keyResolvers.isResolvable() || keyResolvers.isAmbiguous())
            return down("public-key-resolver-not-configured-or-ambiguous");
        if (artifactResolvers == null || !artifactResolvers.isResolvable() || artifactResolvers.isAmbiguous())
            return down("artifact-resolver-not-configured-or-ambiguous");
        try {
            new JdbcShadowEvaluationTrustRegistry(dataSources.get()).verifySchema();
            return HealthCheckResponse.named("shadow-evaluation-trust")
                    .up().withData("registry", "jdbc")
                    .withData("verifier", "configured")\n                    .withData("publicKeyResolver", "configured")\n                    .withData("artifactResolver", "configured").build();
        } catch (RuntimeException exception) {
            return down("schema-or-database-unavailable");
        }
    }

    private HealthCheckResponse down(String reason) {
        return HealthCheckResponse.named("shadow-evaluation-trust")
                .down().withData("reason", reason).build();
    }
}
