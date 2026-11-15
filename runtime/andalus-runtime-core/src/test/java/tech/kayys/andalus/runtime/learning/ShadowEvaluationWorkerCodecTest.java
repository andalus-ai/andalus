package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.harness.evaluation.Evaluator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShadowEvaluationWorkerCodecTest {
    private static final byte[] BASELINE = "baseline-artifact".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CANDIDATE = "candidate-artifact".getBytes(StandardCharsets.UTF_8);

    @Test
    void requestRoundTripCarriesOnlyTypedDataAndVerifiesArtifactAndDatasetDigests() throws Exception {
        var dataset = dataset();
        var baselineDescriptor = new Evaluator.EvaluatorDescriptor("baseline-eval", "1", Map.of("mode", "strict"));
        var candidateDescriptor = new Evaluator.EvaluatorDescriptor("candidate-eval", "2", Map.of("mode", "strict"));
        var baselineManifest = evaluatorManifest("baseline", "1", baselineDescriptor, BASELINE);
        var candidateManifest = evaluatorManifest("candidate", "2", candidateDescriptor, CANDIDATE);
        var datasetManifest = new ShadowEvaluationManifest.DatasetManifest(
                dataset.id(), dataset.version(), "v1", dataset.cases().size(),
                ShadowEvaluationRunner.fingerprint(dataset), "datasets/revision-7", "signer-a", "signature");
        var request = new ShadowEvaluationExecutionBoundary.ExecutionRequest(
                "run-1", proposal(), "approval-1", "baseline", "1", baselineDescriptor,
                baselineManifest, "candidate", "2", candidateDescriptor, candidateManifest,
                dataset, List.of(new ShadowEvaluationRunner.MetricRule("accuracy",
                        ShadowEvaluationReport.Direction.HIGHER_IS_BETTER, 0.01)));

        byte[] encoded = ShadowEvaluationWorkerCodec.encodeRequest(request, datasetManifest, BASELINE, CANDIDATE);
        var decoded = ShadowEvaluationWorkerCodec.decodeRequest(encoded);

        assertThat(decoded.runId()).isEqualTo("run-1");
        assertThat(decoded.dataset()).isEqualTo(dataset);
        assertThat(decoded.proposal()).isEqualTo(proposal());
        assertThat(decoded.baselineArtifact()).containsExactly(BASELINE);
        assertThat(decoded.candidateArtifact()).containsExactly(CANDIDATE);
        assertThat(decoded.metricRules()).hasSize(1);
    }

    @Test
    void rejectsArtifactDigestMismatchBeforePayloadIsAccepted() throws Exception {
        var dataset = dataset();
        var descriptor = new Evaluator.EvaluatorDescriptor("eval", "1", Map.of());
        var manifest = evaluatorManifest("candidate", "1", descriptor, CANDIDATE);
        var request = new ShadowEvaluationExecutionBoundary.ExecutionRequest(
                "run-1", proposal(), "approval-1", "candidate", "1", descriptor, manifest,
                "candidate", "1", descriptor, manifest, dataset,
                List.of(new ShadowEvaluationRunner.MetricRule("accuracy",
                        ShadowEvaluationReport.Direction.HIGHER_IS_BETTER, 0)));
        var datasetManifest = datasetManifest(dataset);

        assertThatThrownBy(() -> ShadowEvaluationWorkerCodec.encodeRequest(
                request, datasetManifest, BASELINE, CANDIDATE))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("digest mismatch");
    }

    @Test
    void responseAndFailureDtosRoundTripWithoutPolymorphicDeserialization() throws Exception {
        var report = new ShadowEvaluationReport("run-1", "proposal-1",
                proposal().subject(), "snapshot-1", "approval-1", "policy-fingerprint",
                "baseline-eval", "1", "baseline-config", "candidate-eval", "2", "candidate-config",
                "baseline", "1", "candidate", "2", "dataset-1", "revision-7", "a".repeat(64),
                ShadowEvaluationReport.Status.PASS,
                List.of(new ShadowEvaluationReport.Metric("accuracy", 0.8, 0.9,
                        ShadowEvaluationReport.Direction.HIGHER_IS_BETTER, 0.05)),
                List.of("evidence:baseline", "evidence:candidate"), Instant.parse("2026-11-15T00:00:00Z"));

        assertThat(ShadowEvaluationWorkerCodec.decodeResponse(
                ShadowEvaluationWorkerCodec.encodeResponse(report))).isEqualTo(report);
        var failure = new ShadowEvaluationWorkerCodec.WorkerFailure("EVALUATOR_FAILED", "evaluation failed");
        assertThat(ShadowEvaluationWorkerCodec.decodeFailure(
                ShadowEvaluationWorkerCodec.encodeFailure(failure))).isEqualTo(failure);
    }

    @Test
    void correlatesTypedResponseAndFailureWithOuterFrameRequestId() throws Exception {
        var requestId = java.util.UUID.randomUUID();
        var report = new ShadowEvaluationReport("run-1", "proposal-1", proposal().subject(),
                "snapshot-1", "approval-1", "policy-fingerprint",
                "baseline-eval", "1", "baseline-config", "candidate-eval", "2", "candidate-config",
                "baseline", "1", "candidate", "2", "dataset-1", "revision-7", "a".repeat(64),
                ShadowEvaluationReport.Status.PASS,
                List.of(new ShadowEvaluationReport.Metric("accuracy", 0.8, 0.9,
                        ShadowEvaluationReport.Direction.HIGHER_IS_BETTER, 0.05)),
                List.of("evidence:one"), Instant.parse("2026-11-15T00:00:00Z"));
        byte[] responseFrame = ShadowEvaluationWorkerProtocol.encode(
                new ShadowEvaluationWorkerProtocol.Frame(requestId,
                        ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_RESPONSE,
                        ShadowEvaluationWorkerCodec.encodeResponse(report)));
        assertThat(ShadowEvaluationWorkerCodec.decodeResponseFrame(responseFrame, requestId)).isEqualTo(report);
        assertThatThrownBy(() -> ShadowEvaluationWorkerCodec.decodeResponseFrame(
                responseFrame, java.util.UUID.randomUUID()))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("uncorrelated");

        var failure = new ShadowEvaluationWorkerCodec.WorkerFailure("FAILED", "safe message");
        byte[] failureFrame = ShadowEvaluationWorkerProtocol.encode(
                new ShadowEvaluationWorkerProtocol.Frame(requestId,
                        ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_FAILURE,
                        ShadowEvaluationWorkerCodec.encodeFailure(failure)));
        assertThat(ShadowEvaluationWorkerCodec.decodeFailureFrame(failureFrame, requestId)).isEqualTo(failure);
    }

    @Test
    void rejectsTrailingBytesAndInvalidArtifactBytes() throws Exception {
        byte[] failure = ShadowEvaluationWorkerCodec.encodeFailure(
                new ShadowEvaluationWorkerCodec.WorkerFailure("FAILED", "safe message"));
        byte[] trailing = java.util.Arrays.copyOf(failure, failure.length + 1);
        assertThatThrownBy(() -> ShadowEvaluationWorkerCodec.decodeFailure(trailing))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("trailing");

        var descriptor = new Evaluator.EvaluatorDescriptor("eval", "1", Map.of());
        var manifest = evaluatorManifest("candidate", "1", descriptor, CANDIDATE);
        assertThatThrownBy(() -> ShadowEvaluationWorkerCodec.verifyArtifactBytes(manifest, BASELINE))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("digest mismatch");
    }

    private static Evaluator.Dataset dataset() {
        return new Evaluator.Dataset("dataset-1", "revision-7", List.of(
                new Evaluator.EvaluationCase("case-1", "input", "expected", Map.of("locale", "en"))));
    }

    private static ShadowEvaluationManifest.DatasetManifest datasetManifest(Evaluator.Dataset dataset) {
        return new ShadowEvaluationManifest.DatasetManifest(dataset.id(), dataset.version(), "v1",
                dataset.cases().size(), ShadowEvaluationRunner.fingerprint(dataset),
                "datasets/revision-7", "signer-a", "signature");
    }

    private static FeedbackAdaptationGate.Proposal proposal() {
        return new FeedbackAdaptationGate.Proposal("proposal-1",
                new FeedbackAggregationRecoveryService.Subject(
                        FeedbackAggregationRecoveryService.SubjectKind.TASK, "task-1"),
                "snapshot-1", "policy-fingerprint");
    }

    private static ShadowEvaluationManifest.EvaluatorArtifactManifest evaluatorManifest(
            String candidate, String version, Evaluator.EvaluatorDescriptor descriptor, byte[] bytes) throws Exception {
        return new ShadowEvaluationManifest.EvaluatorArtifactManifest(
                candidate + "-artifact", "1", "artifacts/" + candidate + ".jar", sha256(bytes),
                candidate, version, descriptor.id(), descriptor.version(),
                descriptor.configurationFingerprint(), "signer-a", "signature");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
