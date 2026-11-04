package tech.kayys.andalus.runtime.learning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import tech.kayys.andalus.harness.evaluation.Evaluator;

import javax.sql.DataSource;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Fail-closed Ed25519 verifier backed by the durable signer registry and deployment-managed
 * public-key/artifact resolvers. No URL fetching, key auto-discovery, or evaluator substitution.
 */
@ApplicationScoped
public class Ed25519ShadowEvaluationManifestVerifier implements ShadowEvaluationManifestVerifier {
    private static final long MAX_ARTIFACT_BYTES = 512L * 1024 * 1024;

    @Inject Instance<DataSource> dataSources;
    @Inject Instance<ShadowEvaluationPublicKeyResolver> keyResolvers;
    @Inject Instance<ShadowEvaluationArtifactResolver> artifactResolvers;

    @Override
    public boolean verifyDataset(ShadowEvaluationManifest.DatasetManifest manifest, Evaluator.Dataset dataset) {
        if (manifest == null || dataset == null) return false;
        try {
            if (!manifest.datasetId().equals(dataset.id())
                    || !manifest.datasetVersion().equals(dataset.version())
                    || manifest.caseCount() != dataset.cases().size()
                    || !manifest.contentFingerprint().equals(ShadowEvaluationRunner.fingerprint(dataset))) return false;
            return verifySignature(manifest.signerId(), manifest.signature(),
                    ShadowEvaluationSigning.datasetPayload(manifest));
        } catch (RuntimeException ex) { return false; }
    }

    @Override
    public boolean verifyEvaluatorArtifact(
            ShadowEvaluationManifest.EvaluatorArtifactManifest manifest, Evaluator evaluator) {
        if (manifest == null || evaluator == null || !unique(dataSources)
                || !unique(keyResolvers) || !unique(artifactResolvers)) return false;
        try {
            if (!verifySignature(manifest.signerId(), manifest.signature(),
                    ShadowEvaluationSigning.evaluatorPayload(manifest))) return false;
            try (ShadowEvaluationArtifactResolver.ResolvedArtifact artifact =
                         artifactResolvers.get().resolve(manifest.artifactReference())) {
                if (artifact.evaluator() != evaluator) return false;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = artifact.content().read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_ARTIFACT_BYTES) return false;
                    digest.update(buffer, 0, read);
                }
                return HexFormat.of().formatHex(digest.digest()).equals(manifest.artifactDigest());
            }
        } catch (Exception ex) { return false; }
    }

    private boolean verifySignature(String signerId, String encodedSignature, byte[] payload) {
        if (!unique(dataSources) || !unique(keyResolvers)) return false;
        try {
            JdbcShadowEvaluationTrustRegistry registry =
                    new JdbcShadowEvaluationTrustRegistry(dataSources.get());
            JdbcShadowEvaluationTrustRegistry.Signer signer = registry.findSigner(signerId).orElse(null);
            if (signer == null || signer.status() != JdbcShadowEvaluationTrustRegistry.SignerStatus.ACTIVE
                    || !"Ed25519".equalsIgnoreCase(signer.signatureAlgorithm())) return false;
            PublicKey key = keyResolvers.get().resolve(signer.id(), signer.publicKeyReference(),
                    signer.signatureAlgorithm()).orElse(null);
            if (key == null || !"Ed25519".equalsIgnoreCase(key.getAlgorithm())) return false;
            byte[] signatureBytes = Base64.getDecoder().decode(encodedSignature);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key); verifier.update(payload); return verifier.verify(signatureBytes);
        } catch (Exception ex) { return false; }
    }

    private static boolean unique(Instance<?> instance) {
        return instance != null && instance.isResolvable() && !instance.isAmbiguous();
    }
}
