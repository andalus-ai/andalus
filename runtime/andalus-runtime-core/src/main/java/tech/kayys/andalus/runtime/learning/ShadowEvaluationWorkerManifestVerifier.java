package tech.kayys.andalus.runtime.learning;

import tech.kayys.andalus.harness.evaluation.Evaluator;

import java.io.IOException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * Worker-local cryptographic gate. It verifies the signed dataset and evaluator manifests
 * against a deployment-pinned trust store before any artifact loader may be called.
 *
 * The trust store must be provisioned independently of the request (for example, from a
 * read-only, image-pinned trust bundle). No key, signer status, or trust decision is accepted
 * from the worker request itself.
 */
public final class ShadowEvaluationWorkerManifestVerifier {

    public interface TrustStore {
        /** Returns a signer only while its key is trusted and active in this worker's trust snapshot. */
        Optional<TrustedSigner> findActiveSigner(String signerId);
    }

    public record TrustedSigner(String signerId, String signatureAlgorithm, PublicKey publicKey) {
        public TrustedSigner {
            if (signerId == null || signerId.isBlank()
                    || !"Ed25519".equalsIgnoreCase(signatureAlgorithm)
                    || publicKey == null
                    || !"EdDSA".equalsIgnoreCase(publicKey.getAlgorithm())
                       && !"Ed25519".equalsIgnoreCase(publicKey.getAlgorithm())) {
                throw new IllegalArgumentException("invalid trusted Ed25519 signer");
            }
        }
    }

    public record VerificationResult(boolean verified, String failureCode) {
        public VerificationResult {
            if (verified && failureCode != null) {
                throw new IllegalArgumentException("verified result cannot contain a failure code");
            }
            if (!verified && (failureCode == null || failureCode.isBlank())) {
                throw new IllegalArgumentException("failed result requires a failure code");
            }
        }
        public static VerificationResult success() {
            return new VerificationResult(true, null);
        }
        public static VerificationResult rejected(String code) {
            return new VerificationResult(false, code);
        }
    }

    private final TrustStore trustStore;

    public ShadowEvaluationWorkerManifestVerifier(TrustStore trustStore) {
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
    }

    /**
     * Verifies all signed metadata, dataset binding, descriptor binding, and exact artifact
     * bytes. A caller must not instantiate or load either evaluator unless this returns verified.
     */
    public VerificationResult verify(ShadowEvaluationWorkerCodec.WorkerRequest request) {
        if (request == null) return VerificationResult.rejected("INVALID_REQUEST");
        try {
            if (!verifyDataset(request.datasetManifest(), request.dataset())) {
                return VerificationResult.rejected("DATASET_MANIFEST_UNTRUSTED");
            }
            if (!verifyEvaluator(request.baselineManifest(), request.baselineDescriptor(),
                    request.baselineCandidateId(), request.baselineCandidateVersion(),
                    request.baselineArtifact())) {
                return VerificationResult.rejected("BASELINE_ARTIFACT_UNTRUSTED");
            }
            if (!verifyEvaluator(request.candidateManifest(), request.candidateDescriptor(),
                    request.candidateId(), request.candidateVersion(), request.candidateArtifact())) {
                return VerificationResult.rejected("CANDIDATE_ARTIFACT_UNTRUSTED");
            }
            return VerificationResult.success();
        } catch (Exception ex) {
            // Do not return key material, signature bytes, artifact paths, or exception details.
            return VerificationResult.rejected("MANIFEST_VERIFICATION_FAILED");
        }
    }

    private boolean verifyDataset(ShadowEvaluationManifest.DatasetManifest manifest, Evaluator.Dataset dataset)
            throws Exception {
        if (manifest == null || dataset == null
                || !manifest.datasetId().equals(dataset.id())
                || !manifest.datasetVersion().equals(dataset.version())
                || manifest.caseCount() != dataset.cases().size()
                || !manifest.contentFingerprint().equals(ShadowEvaluationRunner.fingerprint(dataset))) {
            return false;
        }
        return verifySignature(manifest.signerId(), manifest.signature(),
                ShadowEvaluationSigning.datasetPayload(manifest));
    }

    private boolean verifyEvaluator(ShadowEvaluationManifest.EvaluatorArtifactManifest manifest,
            Evaluator.EvaluatorDescriptor descriptor, String candidateId, String candidateVersion,
            byte[] artifactBytes) throws Exception {
        if (manifest == null || descriptor == null
                || !manifest.candidateId().equals(candidateId)
                || !manifest.candidateVersion().equals(candidateVersion)
                || !manifest.evaluatorId().equals(descriptor.id())
                || !manifest.evaluatorVersion().equals(descriptor.version())
                || !manifest.configurationFingerprint().equals(descriptor.configurationFingerprint())) {
            return false;
        }
        if (!verifySignature(manifest.signerId(), manifest.signature(),
                ShadowEvaluationSigning.evaluatorPayload(manifest))) {
            return false;
        }
        ShadowEvaluationWorkerCodec.verifyArtifactBytes(manifest, artifactBytes);
        return true;
    }

    private boolean verifySignature(String signerId, String encodedSignature, byte[] payload) throws Exception {
        Optional<TrustedSigner> resolved = trustStore.findActiveSigner(signerId);
        if (resolved == null || resolved.isEmpty()) return false;
        TrustedSigner signer = resolved.get();
        if (!signer.signerId().equals(signerId)
                || !"Ed25519".equalsIgnoreCase(signer.signatureAlgorithm())) return false;

        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(encodedSignature);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(signer.publicKey());
        verifier.update(payload);
        return verifier.verify(signatureBytes);
    }
}
