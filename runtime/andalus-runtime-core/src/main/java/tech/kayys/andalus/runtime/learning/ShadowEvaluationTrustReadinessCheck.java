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
    @Inject Instance<DataSource> dataSources;
    @Inject Instance<ShadowEvaluationManifestVerifier> verifiers;
    @Inject Instance<ShadowEvaluationPublicKeyResolver> keyResolvers;
    @Inject Instance<ShadowEvaluationArtifactResolver> artifactResolvers;

    @Override
    public HealthCheckResponse call() {
        if (!unique(dataSources)) return down("datasource-not-configured-or-ambiguous");
        if (!unique(verifiers)) return down("manifest-verifier-not-configured-or-ambiguous");
        if (!unique(keyResolvers)) return down("public-key-resolver-not-configured-or-ambiguous");
        if (!unique(artifactResolvers)) return down("artifact-resolver-not-configured-or-ambiguous");
        try {
            new JdbcShadowEvaluationTrustRegistry(dataSources.get()).verifySchema();
            return HealthCheckResponse.named("shadow-evaluation-trust")
                    .up()
                    .withData("registry", "jdbc")
                    .withData("verifier", "configured")
                    .withData("publicKeyResolver", "configured")
                    .withData("artifactResolver", "configured")
                    .build();
        } catch (RuntimeException exception) {
            return down("schema-or-database-unavailable");
        }
    }

    private static boolean unique(Instance<?> instance) {
        return instance != null && instance.isResolvable() && !instance.isAmbiguous();
    }

    private HealthCheckResponse down(String reason) {
        return HealthCheckResponse.named("shadow-evaluation-trust")
                .down().withData("reason", reason).build();
    }
}
