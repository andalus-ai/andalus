package tech.kayys.andalus.runtime.learning;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcShadowEvaluationTrustRegistryTest {
    private static final String DIGEST = "a".repeat(64);

    @Test
    void persistsExactVersionManifestsAndRecoversAcrossRegistryInstances() throws Exception {
        var source = dataSource();
        schema(source);
        var registry = new JdbcShadowEvaluationTrustRegistry(source);
        registry.registerSigner("release-key-1", "Ed25519", "kms://keys/release-1");
        var dataset = dataset();
        var evaluator = evaluator();

        registry.registerDataset(dataset);
        registry.registerEvaluatorArtifact(evaluator);

        var recovered = new JdbcShadowEvaluationTrustRegistry(source);
        assertThat(recovered.findDataset("quality-set", "v4")).contains(dataset);
        assertThat(recovered.findEvaluatorArtifact("candidate", "v7", "judge", "v2", DIGEST))
                .contains(evaluator);
        // Exact retries do not create alternate versions or mutate manifest identity.
        recovered.registerDataset(dataset);
        recovered.registerEvaluatorArtifact(evaluator);
        assertThat(recovered.findDataset("quality-set", "latest")).isEmpty();
    }

    @Test
    void rejectsManifestReplacementUnderSameImmutableVersion() throws Exception {
        var source = dataSource();
        schema(source);
        var registry = new JdbcShadowEvaluationTrustRegistry(source);
        registry.registerSigner("release-key-1", "Ed25519", "kms://keys/release-1");
        registry.registerDataset(dataset());
        var changed = new ShadowEvaluationManifest.DatasetManifest(
                "quality-set", "v4", "case-v1", 8, "b".repeat(64),
                "dataset://quality-set/v4", "release-key-1", "signature");

        assertThatThrownBy(() -> registry.registerDataset(changed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already bound");
        assertThat(registry.findDataset("quality-set", "v4")).contains(dataset());
    }

    @Test
    void revocationImmediatelyHidesManifestsAndPreventsNewRegistrations() throws Exception {
        var source = dataSource();
        schema(source);
        var registry = new JdbcShadowEvaluationTrustRegistry(source);
        registry.registerSigner("release-key-1", "Ed25519", "kms://keys/release-1");
        registry.registerDataset(dataset());
        registry.registerEvaluatorArtifact(evaluator());

        var revoked = registry.revokeSigner("release-key-1");
        assertThat(revoked.status()).isEqualTo(JdbcShadowEvaluationTrustRegistry.SignerStatus.REVOKED);
        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(registry.findDataset("quality-set", "v4")).isEmpty();
        assertThat(registry.findEvaluatorArtifact("candidate", "v7", "judge", "v2", DIGEST)).isEmpty();
        assertThatThrownBy(() -> registry.registerDataset(new ShadowEvaluationManifest.DatasetManifest(
                "quality-set", "v5", "case-v1", 8, DIGEST,
                "dataset://quality-set/v5", "release-key-1", "signature")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revoked");
        assertThatThrownBy(() -> registry.registerSigner("release-key-1", "Ed25519", "kms://keys/release-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void signerIdCannotBeReboundToDifferentKeyMetadata() throws Exception {
        var source = dataSource();
        schema(source);
        var registry = new JdbcShadowEvaluationTrustRegistry(source);
        registry.registerSigner("release-key-1", "Ed25519", "kms://keys/release-1");

        assertThatThrownBy(() -> registry.registerSigner("release-key-1", "RSA", "kms://keys/other"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ShadowEvaluationManifest.DatasetManifest dataset() {
        return new ShadowEvaluationManifest.DatasetManifest("quality-set", "v4", "case-v1", 8,
                DIGEST, "dataset://quality-set/v4", "release-key-1", "signed-dataset-manifest");
    }

    private static ShadowEvaluationManifest.EvaluatorArtifactManifest evaluator() {
        return new ShadowEvaluationManifest.EvaluatorArtifactManifest(
                "judge-artifact", "v2", "artifact://judge/v2", DIGEST,
                "candidate", "v7", "judge", "v2", DIGEST,
                "release-key-1", "signed-evaluator-manifest");
    }

    private static JdbcDataSource dataSource() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:trust_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        source.setUser("sa");
        source.setPassword("");
        return source;
    }

    private static void schema(JdbcDataSource source) throws Exception {
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE andalus_shadow_trusted_signer (
                        signer_id VARCHAR(255) PRIMARY KEY, signature_algorithm VARCHAR(64) NOT NULL,
                        public_key_reference VARCHAR(2048) NOT NULL, signer_status VARCHAR(16) NOT NULL,
                        registered_at_epoch_millis BIGINT NOT NULL, revoked_at_epoch_millis BIGINT)
                    """);
            s.execute("""
                    CREATE TABLE andalus_shadow_dataset_manifest (
                        dataset_id VARCHAR(255) NOT NULL, dataset_version VARCHAR(128) NOT NULL,
                        schema_version VARCHAR(128) NOT NULL, case_count INTEGER NOT NULL,
                        content_fingerprint VARCHAR(64) NOT NULL, source_reference VARCHAR(2048) NOT NULL,
                        signer_id VARCHAR(255) NOT NULL, signature VARCHAR(4096) NOT NULL,
                        registered_at_epoch_millis BIGINT NOT NULL,
                        PRIMARY KEY (dataset_id, dataset_version),
                        FOREIGN KEY (signer_id) REFERENCES andalus_shadow_trusted_signer(signer_id))
                    """);
            s.execute("""
                    CREATE TABLE andalus_shadow_evaluator_manifest (
                        candidate_id VARCHAR(255) NOT NULL, candidate_version VARCHAR(128) NOT NULL,
                        evaluator_id VARCHAR(255) NOT NULL, evaluator_version VARCHAR(128) NOT NULL,
                        configuration_fingerprint VARCHAR(64) NOT NULL, artifact_id VARCHAR(255) NOT NULL,
                        artifact_version VARCHAR(128) NOT NULL, artifact_reference VARCHAR(2048) NOT NULL,
                        artifact_digest VARCHAR(64) NOT NULL, signer_id VARCHAR(255) NOT NULL,
                        signature VARCHAR(4096) NOT NULL, registered_at_epoch_millis BIGINT NOT NULL,
                        PRIMARY KEY (candidate_id, candidate_version, evaluator_id, evaluator_version,
                                     configuration_fingerprint),
                        FOREIGN KEY (signer_id) REFERENCES andalus_shadow_trusted_signer(signer_id))
                    """);
        }
    }
}
