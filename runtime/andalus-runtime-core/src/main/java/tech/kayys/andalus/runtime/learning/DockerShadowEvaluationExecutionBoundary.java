package tech.kayys.andalus.runtime.learning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.kayys.andalus.harness.evaluation.Evaluator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Docker-backed OS-isolated execution adapter. It transports exact signed artifact bytes and
 * dataset manifests to the worker; it never constructs or loads candidate evaluators in this JVM.
 *
 * A configured immutable image digest is necessary but not sufficient for production readiness:
 * deployment must also provide the image-baked worker bootstrap/trust store and validate the
 * Docker daemon as trusted infrastructure.
 */
@ApplicationScoped
public class DockerShadowEvaluationExecutionBoundary implements ShadowEvaluationExecutionBoundary {
    private static final int MAX_ARTIFACT_BYTES = 7 * 1024 * 1024;

    @Inject Instance<ShadowEvaluationManifestVerifier> manifestVerifiers;
    @Inject Instance<ShadowEvaluationTrustRegistry> trustRegistries;
    @Inject Instance<ShadowEvaluationArtifactContentResolver> artifactResolvers;

    @ConfigProperty(name = "andalus.shadow-worker.docker-executable", defaultValue = "")
    String dockerExecutable;

    @ConfigProperty(name = "andalus.shadow-worker.image", defaultValue = "")
    String workerImage;

    @ConfigProperty(name = "andalus.shadow-worker.timeout-seconds", defaultValue = "300")
    long timeoutSeconds;

    @ConfigProperty(name = "andalus.shadow-worker.memory-mib", defaultValue = "1024")
    long memoryMiB;

    @ConfigProperty(name = "andalus.shadow-worker.cpus", defaultValue = "1.0")
    double cpus;

    @ConfigProperty(name = "andalus.shadow-worker.pids", defaultValue = "128")
    int pids;

    @ConfigProperty(name = "andalus.shadow-worker.tmpfs-mib", defaultValue = "64")
    int tmpfsMiB;

    @ConfigProperty(name = "andalus.shadow-worker.max-stderr-bytes", defaultValue = "65536")
    int maxStderrBytes;

    @Override
    public IsolationLevel isolationLevel() {
        return configured() ? IsolationLevel.CONTAINER : IsolationLevel.NONE;
    }

    @Override
    public CompletionStage<ShadowEvaluationReport> execute(ExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            if (!configured()) throw new IllegalStateException("isolated shadow worker is not configured");
            ShadowEvaluationManifestVerifier verifier = manifestVerifiers.get();
            ShadowEvaluationTrustRegistry registry = trustRegistries.get();
            ShadowEvaluationArtifactContentResolver resolver = artifactResolvers.get();

            ShadowEvaluationManifest.DatasetManifest datasetManifest =
                    registry.findDataset(request.dataset().id(), request.dataset().version())
                            .orElseThrow(() -> new IllegalStateException("trusted dataset manifest not found"));
            if (!verifier.verifyDataset(datasetManifest, request.dataset()))
                throw new IllegalStateException("dataset manifest verification failed");

            verifyEvaluatorBinding(request.baselineCandidateId(), request.baselineCandidateVersion(),
                    request.baselineDescriptor(), request.baselineArtifact());
            verifyEvaluatorBinding(request.candidateId(), request.candidateVersion(),
                    request.candidateDescriptor(), request.candidateArtifact());

            byte[] baselineBytes = readArtifact(resolver, request.baselineArtifact());
            byte[] candidateBytes = readArtifact(resolver, request.candidateArtifact());

            UUID requestId = UUID.randomUUID();
            byte[] encoded = ShadowEvaluationWorkerCodec.encodeRequestFrame(
                    requestId, request, datasetManifest, baselineBytes, candidateBytes);
            byte[] response = new DockerShadowEvaluationWorkerTransport(
                    dockerExecutable, workerImage, limits()).exchange(encoded);

            ShadowEvaluationWorkerProtocol.Frame frame = ShadowEvaluationWorkerProtocol.decode(response);
            if (!frame.requestId().equals(requestId))
                throw new IOException("worker response correlation mismatch");
            if (frame.type() == ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_FAILURE) {
                ShadowEvaluationWorkerCodec.WorkerFailure failure =
                        ShadowEvaluationWorkerCodec.decodeFailureFrame(response, requestId);
                throw new IllegalStateException("isolated shadow worker rejected request: " + failure.code());
            }
            ShadowEvaluationReport report = ShadowEvaluationWorkerCodec.decodeResponseFrame(response, requestId);
            validateReport(request, report, datasetManifest);
            return CompletableFuture.completedFuture(report);
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private boolean configured() {
        return nonBlank(dockerExecutable) && java.nio.file.Path.of(dockerExecutable).isAbsolute()
                && workerImage != null && workerImage.matches("[a-zA-Z0-9][a-zA-Z0-9._/:@-]*@sha256:[a-f0-9]{64}")
                && unique(manifestVerifiers) && unique(trustRegistries) && unique(artifactResolvers)
                && validLimits();
    }

    private boolean validLimits() {
        try {
            limits();
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private DockerShadowEvaluationWorkerTransport.Limits limits() {
        return new DockerShadowEvaluationWorkerTransport.Limits(memoryMiB, cpus, pids, tmpfsMiB,
                java.time.Duration.ofSeconds(timeoutSeconds), maxStderrBytes);
    }

    private void verifyEvaluatorBinding(String candidateId, String candidateVersion,
            Evaluator.EvaluatorDescriptor descriptor,
            ShadowEvaluationManifest.EvaluatorArtifactManifest manifest) {
        if (!candidateId.equals(manifest.candidateId())
                || !candidateVersion.equals(manifest.candidateVersion())
                || !descriptor.id().equals(manifest.evaluatorId())
                || !descriptor.version().equals(manifest.evaluatorVersion())
                || !descriptor.configurationFingerprint().equals(manifest.configurationFingerprint())
                || !manifestVerifiers.get().verifyEvaluatorArtifact(manifest)) {
            throw new IllegalStateException("trusted evaluator manifest verification failed");
        }
    }

    private static byte[] readArtifact(ShadowEvaluationArtifactContentResolver resolver,
            ShadowEvaluationManifest.EvaluatorArtifactManifest manifest) throws IOException {
        try (ShadowEvaluationArtifactContentResolver.ResolvedContent resolved =
                     resolver.resolve(manifest.artifactReference());
             InputStream input = resolved.content();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() > MAX_ARTIFACT_BYTES - count)
                    throw new IOException("evaluator artifact exceeds worker transfer limit");
                output.write(buffer, 0, count);
            }
            byte[] bytes = output.toByteArray();
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (bytes.length == 0 || !digest.equals(manifest.artifactDigest()))
                throw new IOException("resolved evaluator bytes do not match the signed manifest");
            return bytes;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void validateReport(ExecutionRequest request, ShadowEvaluationReport report,
            ShadowEvaluationManifest.DatasetManifest datasetManifest) {
        if (!report.runId().equals(request.runId())
                || !report.proposalId().equals(request.proposal().id())
                || !report.subject().equals(request.proposal().subject())
                || !report.sourceSnapshotId().equals(request.proposal().sourceSnapshotId())
                || !report.approvalId().equals(request.approvalId())
                || !report.policyFingerprint().equals(request.proposal().policyFingerprint())
                || !report.baselineCandidateId().equals(request.baselineCandidateId())
                || !report.baselineCandidateVersion().equals(request.baselineCandidateVersion())
                || !report.candidateId().equals(request.candidateId())
                || !report.candidateVersion().equals(request.candidateVersion())
                || !report.datasetId().equals(request.dataset().id())
                || !report.datasetVersion().equals(request.dataset().version())
                || !report.datasetFingerprint().equals(datasetManifest.contentFingerprint())
                || !report.baselineEvaluatorId().equals(request.baselineDescriptor().id())
                || !report.baselineEvaluatorVersion().equals(request.baselineDescriptor().version())
                || !report.baselineEvaluatorConfigFingerprint().equals(request.baselineDescriptor().configurationFingerprint())
                || !report.candidateEvaluatorId().equals(request.candidateDescriptor().id())
                || !report.candidateEvaluatorVersion().equals(request.candidateDescriptor().version())
                || !report.candidateEvaluatorConfigFingerprint().equals(request.candidateDescriptor().configurationFingerprint())) {
            throw new IllegalStateException("worker report provenance does not match the execution request");
        }
    }

    private static boolean unique(Instance<?> instance) {
        return instance != null && instance.isResolvable() && !instance.isAmbiguous();
    }

    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
}
