package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.harness.evaluation.Evaluator;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ShadowEvaluationWorkerEngineTest {

    @Test
    void rejectsUntrustedManifestBeforeLoadingAnyArtifact() throws Exception {
        Fixture fixture = new Fixture();
        var engine = new ShadowEvaluationWorkerEngine(
                new ShadowEvaluationWorkerManifestVerifier(id -> Optional.empty()),
                (bytes, descriptor) -> { fixture.loads.incrementAndGet(); return evaluator(); },
                new ShadowEvaluationRunner());
        byte[] request = fixture.frame();

        byte[] response = engine.handle(request).toCompletableFuture().get();
        var frame = ShadowEvaluationWorkerProtocol.decode(response);
        var failure = ShadowEvaluationWorkerCodec.decodeFailure(frame.payload());

        assertEquals(ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_FAILURE, frame.type());
        assertEquals("DATASET_MANIFEST_UNTRUSTED", failure.code());
        assertEquals(0, fixture.loads.get());
    }

    @Test
    void verifiesThenLoadsBothArtifactsAndReturnsCorrelatedReport() throws Exception {
        Fixture fixture = new Fixture();
        var engine = new ShadowEvaluationWorkerEngine(
                new ShadowEvaluationWorkerManifestVerifier(id ->
                        Optional.of(new ShadowEvaluationWorkerManifestVerifier.TrustedSigner(
                                id, "Ed25519", fixture.keys.getPublic()))),
                (bytes, descriptor) -> {
                    fixture.loads.incrementAndGet();
                    return evaluator();
                },
                new ShadowEvaluationRunner());
        UUID requestId = UUID.randomUUID();

        byte[] response = engine.handle(fixture.frame(requestId)).toCompletableFuture().get();
        var report = ShadowEvaluationWorkerCodec.decodeResponseFrame(response, requestId);

        assertEquals(2, fixture.loads.get());
        assertEquals("proposal-1", report.proposalId());
        assertEquals("candidate-1", report.candidateId());
        assertEquals(ShadowEvaluationReport.Status.PASS, report.status());
    }

    private static Evaluator evaluator() {
        return item -> java.util.concurrent.CompletableFuture.completedFuture(
                new Evaluator.CaseResult(item.id(), Map.of("accuracy", 0.95), true, "not persisted"));
    }

    private static final class Fixture {
        final KeyPair keys;
        final AtomicInteger loads = new AtomicInteger();
        final Evaluator.Dataset dataset = new Evaluator.Dataset("dataset-1", "v1",
                List.of(new Evaluator.EvaluationCase("case-1", "input", "expected", Map.of())));
        final Evaluator.EvaluatorDescriptor baselineDescriptor =
                new Evaluator.EvaluatorDescriptor("baseline-evaluator", "1", Map.of("profile", "strict"));
        final Evaluator.EvaluatorDescriptor candidateDescriptor =
                new Evaluator.EvaluatorDescriptor("candidate-evaluator", "1", Map.of("profile", "strict"));

        Fixture() throws Exception { keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }

        byte[] frame() throws Exception { return frame(UUID.randomUUID()); }

        byte[] frame(UUID requestId) throws Exception {
            byte[] baselineBytes = new byte[]{1, 2, 3};
            byte[] candidateBytes = new byte[]{4, 5, 6};
            var datasetUnsigned = new ShadowEvaluationManifest.DatasetManifest(
                    dataset.id(), dataset.version(), "schema-v1", 1, ShadowEvaluationRunner.fingerprint(dataset),
                    "dataset-ref", "trusted-worker-key", "pending");
            var datasetManifest = new ShadowEvaluationManifest.DatasetManifest(
                    datasetUnsigned.datasetId(), datasetUnsigned.datasetVersion(), datasetUnsigned.schemaVersion(),
                    datasetUnsigned.caseCount(), datasetUnsigned.contentFingerprint(), datasetUnsigned.sourceReference(),
                    datasetUnsigned.signerId(), sign(ShadowEvaluationSigning.datasetPayload(datasetUnsigned)));
            var baselineManifest = evaluatorManifest("baseline-1", "1", baselineDescriptor, baselineBytes);
            var candidateManifest = evaluatorManifest("candidate-1", "1", candidateDescriptor, candidateBytes);
            var proposal = new FeedbackAdaptationGate.Proposal("proposal-1",
                    new FeedbackAggregationRecoveryService.Subject(
                            FeedbackAggregationRecoveryService.SubjectKind.TASK, "task-1"),
                    "snapshot-1", "a".repeat(64));
            var request = new ShadowEvaluationExecutionBoundary.ExecutionRequest(
                    "run-1", proposal, "approval-1", "baseline-1", "1", baselineDescriptor,
                    baselineManifest, "candidate-1", "1", candidateDescriptor, candidateManifest,
                    dataset, List.of(new ShadowEvaluationRunner.MetricRule("accuracy",
                    ShadowEvaluationReport.Direction.HIGHER_IS_BETTER, 0.0)));
            return ShadowEvaluationWorkerCodec.encodeRequestFrame(
                    requestId, request, datasetManifest, baselineBytes, candidateBytes);
        }

        private ShadowEvaluationManifest.EvaluatorArtifactManifest evaluatorManifest(
                String candidateId, String version, Evaluator.EvaluatorDescriptor descriptor, byte[] bytes)
                throws Exception {
            var unsigned = new ShadowEvaluationManifest.EvaluatorArtifactManifest(
                    candidateId + "-artifact", "v1", "artifacts/" + candidateId + ".jar",
                    sha256(bytes), candidateId, version, descriptor.id(), descriptor.version(),
                    descriptor.configurationFingerprint(), "trusted-worker-key", "pending");
            return new ShadowEvaluationManifest.EvaluatorArtifactManifest(
                    unsigned.artifactId(), unsigned.artifactVersion(), unsigned.artifactReference(),
                    unsigned.artifactDigest(), unsigned.candidateId(), unsigned.candidateVersion(),
                    unsigned.evaluatorId(), unsigned.evaluatorVersion(), unsigned.configurationFingerprint(),
                    unsigned.signerId(), sign(ShadowEvaluationSigning.evaluatorPayload(unsigned)));
        }

        private String sign(byte[] payload) throws Exception {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keys.getPrivate());
            signer.update(payload);
            return Base64.getEncoder().encodeToString(signer.sign());
        }

        private static String sha256(byte[] bytes) throws Exception {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        }
    }
}
