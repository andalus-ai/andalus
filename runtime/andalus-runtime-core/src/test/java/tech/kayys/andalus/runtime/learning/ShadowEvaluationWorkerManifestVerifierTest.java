package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.harness.evaluation.Evaluator;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ShadowEvaluationWorkerManifestVerifierTest {

    @Test
    void verifiesDatasetAndBothEvaluatorSignaturesBeforeExecution() throws Exception {
        KeyPair pair = keyPair();
        var request = request(pair);
        var verifier = new ShadowEvaluationWorkerManifestVerifier(id ->
                "worker-signer".equals(id)
                        ? Optional.of(new ShadowEvaluationWorkerManifestVerifier.TrustedSigner(
                                id, "Ed25519", pair.getPublic()))
                        : Optional.empty());

        var result = verifier.verify(request);

        assertTrue(result.verified());
        assertNull(result.failureCode());
    }

    @Test
    void rejectsUnknownOrRevokedSigner() throws Exception {
        var request = request(keyPair());
        var verifier = new ShadowEvaluationWorkerManifestVerifier(id -> Optional.empty());

        var result = verifier.verify(request);

        assertFalse(result.verified());
        assertEquals("DATASET_MANIFEST_UNTRUSTED", result.failureCode());
    }

    @Test
    void rejectsArtifactBytesThatDifferFromSignedDigest() throws Exception {
        KeyPair pair = keyPair();
        var valid = request(pair);
        var tampered = new ShadowEvaluationWorkerCodec.WorkerRequest(
                valid.runId(), valid.proposal(), valid.approvalId(),
                valid.baselineCandidateId(), valid.baselineCandidateVersion(),
                valid.baselineDescriptor(), valid.baselineManifest(), new byte[]{9, 9, 9},
                valid.candidateId(), valid.candidateVersion(),
                valid.candidateDescriptor(), valid.candidateManifest(), valid.candidateArtifact(),
                valid.datasetManifest(), valid.dataset(), valid.metricRules());
        var verifier = new ShadowEvaluationWorkerManifestVerifier(id ->
                Optional.of(new ShadowEvaluationWorkerManifestVerifier.TrustedSigner(
                        id, "Ed25519", pair.getPublic())));

        var result = verifier.verify(tampered);

        assertFalse(result.verified());
        assertEquals("BASELINE_ARTIFACT_UNTRUSTED", result.failureCode());
    }

    private static ShadowEvaluationWorkerCodec.WorkerRequest request(KeyPair pair) throws Exception {
        var dataset = new Evaluator.Dataset("golden-set", "v1",
                java.util.List.of(new Evaluator.EvaluationCase("case-1", "input", "expected", Map.of())));
        String datasetFingerprint = ShadowEvaluationRunner.fingerprint(dataset);
        var datasetUnsigned = new ShadowEvaluationManifest.DatasetManifest(
                dataset.id(), dataset.version(), "schema-v1", 1, datasetFingerprint,
                "dataset://golden-set/v1", "worker-signer", "pending");
        var datasetManifest = new ShadowEvaluationManifest.DatasetManifest(
                datasetUnsigned.datasetId(), datasetUnsigned.datasetVersion(), datasetUnsigned.schemaVersion(),
                datasetUnsigned.caseCount(), datasetUnsigned.contentFingerprint(), datasetUnsigned.sourceReference(),
                datasetUnsigned.signerId(), sign(pair, ShadowEvaluationSigning.datasetPayload(datasetUnsigned)));

        byte[] baselineBytes = new byte[]{1, 2, 3};
        byte[] candidateBytes = new byte[]{4, 5, 6};
        var baselineDescriptor = new Evaluator.EvaluatorDescriptor("baseline-eval", "1", Map.of("mode", "safe"));
        var candidateDescriptor = new Evaluator.EvaluatorDescriptor("candidate-eval", "2", Map.of("mode", "safe"));
        var baselineManifest = evaluatorManifest(pair, "baseline", "1", baselineDescriptor, baselineBytes);
        var candidateManifest = evaluatorManifest(pair, "candidate", "2", candidateDescriptor, candidateBytes);
        var proposal = new FeedbackAdaptationGate.Proposal("proposal-1",
                new FeedbackAggregationRecoveryService.Subject(
                        FeedbackAggregationRecoveryService.SubjectKind.TASK, "task-1"),
                "snapshot-1", "a".repeat(64));
        return new ShadowEvaluationWorkerCodec.WorkerRequest(
                "run-1", proposal, "approval-1", "baseline", "1",
                baselineDescriptor, baselineManifest, baselineBytes,
                "candidate", "2", candidateDescriptor, candidateManifest, candidateBytes,
                datasetManifest, dataset,
                java.util.List.of(new ShadowEvaluationRunner.MetricRule("accuracy",
                        ShadowEvaluationReport.Direction.HIGHER_IS_BETTER, 0.01)));
    }

    private static ShadowEvaluationManifest.EvaluatorArtifactManifest evaluatorManifest(
            KeyPair pair, String candidate, String version, Evaluator.EvaluatorDescriptor descriptor,
            byte[] artifact) throws Exception {
        var unsigned = new ShadowEvaluationManifest.EvaluatorArtifactManifest(
                candidate + "-artifact", "1", "artifacts/" + candidate + ".jar", sha256(artifact),
                candidate, version, descriptor.id(), descriptor.version(),
                descriptor.configurationFingerprint(), "worker-signer", "pending");
        return new ShadowEvaluationManifest.EvaluatorArtifactManifest(
                unsigned.artifactId(), unsigned.artifactVersion(), unsigned.artifactReference(),
                unsigned.artifactDigest(), unsigned.candidateId(), unsigned.candidateVersion(),
                unsigned.evaluatorId(), unsigned.evaluatorVersion(), unsigned.configurationFingerprint(),
                unsigned.signerId(), sign(pair, ShadowEvaluationSigning.evaluatorPayload(unsigned)));
    }

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String sign(KeyPair pair, byte[] payload) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(payload);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
