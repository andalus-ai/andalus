package tech.kayys.andalus.runtime.learning;

import tech.kayys.andalus.harness.evaluation.Evaluator;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * One-request worker engine. This class must execute inside the deployment's OS-isolated
 * container or microVM; it is not an isolation mechanism by itself.
 */
public final class ShadowEvaluationWorkerEngine {
    /**
     * Deployment-owned loader. It receives only the exact byte array verified by the manifest
     * verifier and must not resolve references, fetch code, or use host-side application state.
     */
    @FunctionalInterface
    public interface VerifiedArtifactLoader {
        Evaluator load(byte[] verifiedArtifactBytes, Evaluator.EvaluatorDescriptor descriptor) throws Exception;
    }

    private final ShadowEvaluationWorkerManifestVerifier verifier;
    private final VerifiedArtifactLoader artifactLoader;
    private final ShadowEvaluationRunner runner;

    public ShadowEvaluationWorkerEngine(ShadowEvaluationWorkerManifestVerifier verifier,
            VerifiedArtifactLoader artifactLoader, ShadowEvaluationRunner runner) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.artifactLoader = Objects.requireNonNull(artifactLoader, "artifactLoader");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /** Returns a correlated response/failure frame; malformed outer frames fail exceptionally. */
    public CompletionStage<byte[]> handle(byte[] encodedFrame) {
        final ShadowEvaluationWorkerProtocol.Frame frame;
        try {
            frame = ShadowEvaluationWorkerProtocol.decode(encodedFrame);
            if (frame.type() != ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_REQUEST)
                throw new IOException("execute request required");
        } catch (IOException | RuntimeException malformed) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid worker request frame"));
        }

        final ShadowEvaluationWorkerCodec.WorkerRequest request;
        try {
            request = ShadowEvaluationWorkerCodec.decodeRequest(frame.payload());
        } catch (IOException malformed) {
            return CompletableFuture.completedFuture(failure(frame.requestId(), "INVALID_REQUEST",
                    "Worker request payload is invalid"));
        }

        // No class loading or evaluator construction is allowed before this complete verification.
        ShadowEvaluationWorkerManifestVerifier.VerificationResult trust = verifier.verify(request);
        if (!trust.verified()) {
            return CompletableFuture.completedFuture(failure(frame.requestId(), trust.failureCode(),
                    "Worker manifest verification rejected the request"));
        }

        final Evaluator baseline;
        final Evaluator candidate;
        try {
            baseline = Objects.requireNonNull(
                    artifactLoader.load(request.baselineArtifact(), request.baselineDescriptor()),
                    "baseline evaluator");
            candidate = Objects.requireNonNull(
                    artifactLoader.load(request.candidateArtifact(), request.candidateDescriptor()),
                    "candidate evaluator");
        } catch (Exception loadFailure) {
            return CompletableFuture.completedFuture(failure(frame.requestId(), "ARTIFACT_LOAD_FAILED",
                    "Verified evaluator artifact could not be loaded"));
        }

        ShadowEvaluationRunner.RunRequest run = new ShadowEvaluationRunner.RunRequest(
                request.runId(), request.proposal(), request.approvalId(),
                request.baselineCandidateId(), request.baselineCandidateVersion(),
                baseline, request.baselineDescriptor(),
                request.candidateId(), request.candidateVersion(),
                candidate, request.candidateDescriptor(),
                request.dataset(), request.metricRules());
        try {
            return runner.evaluate(run).handle((report, error) -> {
                if (error != null)
                    return failure(frame.requestId(), "EVALUATION_FAILED",
                            "Isolated evaluator execution did not complete successfully");
                if (!matchesRequest(report, request))
                    return failure(frame.requestId(), "REPORT_PROVENANCE_MISMATCH",
                            "Worker report provenance does not match the request");
                try {
                    byte[] payload = ShadowEvaluationWorkerCodec.encodeResponse(report);
                    return ShadowEvaluationWorkerProtocol.encode(new ShadowEvaluationWorkerProtocol.Frame(
                            frame.requestId(), ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_RESPONSE, payload));
                } catch (IOException encodingFailure) {
                    return failure(frame.requestId(), "RESPONSE_ENCODING_FAILED",
                            "Worker response could not be encoded");
                }
            });
        } catch (RuntimeException startFailure) {
            return CompletableFuture.completedFuture(failure(frame.requestId(), "EVALUATION_FAILED",
                    "Isolated evaluator execution could not be started"));
        }
    }

    private static boolean matchesRequest(ShadowEvaluationReport report,
            ShadowEvaluationWorkerCodec.WorkerRequest request) {
        return report != null
                && report.runId().equals(request.runId())
                && report.proposalId().equals(request.proposal().id())
                && report.subject().equals(request.proposal().subject())
                && report.sourceSnapshotId().equals(request.proposal().sourceSnapshotId())
                && report.approvalId().equals(request.approvalId())
                && report.policyFingerprint().equals(request.proposal().policyFingerprint())
                && report.baselineCandidateId().equals(request.baselineCandidateId())
                && report.baselineCandidateVersion().equals(request.baselineCandidateVersion())
                && report.candidateId().equals(request.candidateId())
                && report.candidateVersion().equals(request.candidateVersion())
                && report.datasetId().equals(request.dataset().id())
                && report.datasetVersion().equals(request.dataset().version())
                && report.datasetFingerprint().equals(ShadowEvaluationRunner.fingerprint(request.dataset()))
                && report.baselineEvaluatorId().equals(request.baselineDescriptor().id())
                && report.baselineEvaluatorVersion().equals(request.baselineDescriptor().version())
                && report.baselineEvaluatorConfigFingerprint().equals(request.baselineDescriptor().configurationFingerprint())
                && report.candidateEvaluatorId().equals(request.candidateDescriptor().id())
                && report.candidateEvaluatorVersion().equals(request.candidateDescriptor().version())
                && report.candidateEvaluatorConfigFingerprint().equals(request.candidateDescriptor().configurationFingerprint());
    }

    private static byte[] failure(java.util.UUID requestId, String code, String message) {
        try {
            byte[] payload = ShadowEvaluationWorkerCodec.encodeFailure(
                    new ShadowEvaluationWorkerCodec.WorkerFailure(code, message));
            return ShadowEvaluationWorkerProtocol.encode(new ShadowEvaluationWorkerProtocol.Frame(
                    requestId, ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_FAILURE, payload));
        } catch (IOException impossible) {
            throw new IllegalStateException("bounded worker failure encoding failed", impossible);
        }
    }
}
