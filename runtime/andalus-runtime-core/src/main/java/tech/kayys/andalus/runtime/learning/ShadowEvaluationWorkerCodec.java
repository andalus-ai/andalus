package tech.kayys.andalus.runtime.learning;

import tech.kayys.andalus.harness.evaluation.Evaluator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Strict, versioned binary DTO codec for the shadow worker payload.
 *
 * No Java serialization, polymorphic JSON binding, class names, or arbitrary object graphs
 * cross the process boundary. Artifact bytes are included so the worker can independently
 * verify each SHA-256 digest before loading any evaluator code.
 */
public final class ShadowEvaluationWorkerCodec {
    private static final int PAYLOAD_MAGIC = 0x53484457; // SHDW
    private static final short PAYLOAD_VERSION = 1;
    private static final int MAX_STRING_BYTES = 64 * 1024;
    private static final int MAX_CASES = 10_000;
    private static final int MAX_METADATA = 256;
    private static final int MAX_METRICS = 256;
    private static final int MAX_ARTIFACT_BYTES = 7 * 1024 * 1024;
    private static final int MAX_EVIDENCE = 2048;

    public record WorkerRequest(
            String runId,
            FeedbackAdaptationGate.Proposal proposal,
            String approvalId,
            String baselineCandidateId,
            String baselineCandidateVersion,
            Evaluator.EvaluatorDescriptor baselineDescriptor,
            ShadowEvaluationManifest.EvaluatorArtifactManifest baselineManifest,
            byte[] baselineArtifact,
            String candidateId,
            String candidateVersion,
            Evaluator.EvaluatorDescriptor candidateDescriptor,
            ShadowEvaluationManifest.EvaluatorArtifactManifest candidateManifest,
            byte[] candidateArtifact,
            ShadowEvaluationManifest.DatasetManifest datasetManifest,
            Evaluator.Dataset dataset,
            List<ShadowEvaluationRunner.MetricRule> metricRules) {
        public WorkerRequest {
            baselineArtifact = baselineArtifact.clone();
            candidateArtifact = candidateArtifact.clone();
            metricRules = List.copyOf(metricRules);
        }
        @Override public byte[] baselineArtifact() { return baselineArtifact.clone(); }
        @Override public byte[] candidateArtifact() { return candidateArtifact.clone(); }
    }

    public record WorkerFailure(String code, String message) {
        public WorkerFailure {
            required(code, 64, "failure code");
            required(message, 2048, "failure message");
        }
    }

    private ShadowEvaluationWorkerCodec() { }

    public static byte[] encodeRequest(ShadowEvaluationExecutionBoundary.ExecutionRequest request,
            ShadowEvaluationManifest.DatasetManifest datasetManifest,
            byte[] baselineArtifact, byte[] candidateArtifact) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(datasetManifest, "datasetManifest");
        verifyRequestBindings(request, datasetManifest, baselineArtifact, candidateArtifact);
        return write(out -> {
            header(out, 1);
            string(out, request.runId());
            string(out, request.proposal().id());
            out.writeByte(request.proposal().subject().kind().ordinal());
            string(out, request.proposal().subject().id());
            string(out, request.proposal().sourceSnapshotId());
            string(out, request.proposal().policyFingerprint());
            string(out, request.approvalId());
            string(out, request.baselineCandidateId());
            string(out, request.baselineCandidateVersion());
            descriptor(out, request.baselineDescriptor());
            manifest(out, request.baselineArtifact());
            bytes(out, baselineArtifact, MAX_ARTIFACT_BYTES);
            string(out, request.candidateId());
            string(out, request.candidateVersion());
            descriptor(out, request.candidateDescriptor());
            manifest(out, request.candidateArtifact());
            bytes(out, candidateArtifact, MAX_ARTIFACT_BYTES);
            datasetManifest(out, datasetManifest);
            dataset(out, request.dataset());
            out.writeInt(request.metricRules().size());
            for (var rule : request.metricRules()) {
                string(out, rule.name());
                out.writeByte(rule.direction().ordinal());
                out.writeDouble(rule.minimumImprovement());
            }
        });
    }

    public static WorkerRequest decodeRequest(byte[] payload) throws IOException {
        try (DataInputStream in = input(payload)) {
            header(in, 1);
            String runId = string(in);
            String proposalId = string(in);
            var kind = enumValue(FeedbackAggregationRecoveryService.SubjectKind.values(), in.readUnsignedByte(), "subject kind");
            var subject = new FeedbackAggregationRecoveryService.Subject(kind, string(in));
            var proposal = new FeedbackAdaptationGate.Proposal(proposalId, subject, string(in), string(in));
            String approvalId = string(in);
            String baselineId = string(in);
            String baselineVersion = string(in);
            var baselineDescriptor = descriptor(in);
            var baselineManifest = manifest(in);
            byte[] baselineBytes = bytes(in, MAX_ARTIFACT_BYTES);
            String candidateId = string(in);
            String candidateVersion = string(in);
            var candidateDescriptor = descriptor(in);
            var candidateManifest = manifest(in);
            byte[] candidateBytes = bytes(in, MAX_ARTIFACT_BYTES);
            var datasetManifest = datasetManifest(in);
            var dataset = dataset(in);
            int ruleCount = count(in, MAX_METRICS);
            if (ruleCount == 0) throw new IOException("worker request has no metric rules");
            List<ShadowEvaluationRunner.MetricRule> rules = new ArrayList<>(ruleCount);
            for (int i = 0; i < ruleCount; i++) {
                rules.add(new ShadowEvaluationRunner.MetricRule(string(in),
                        enumValue(ShadowEvaluationReport.Direction.values(), in.readUnsignedByte(), "metric direction"),
                        in.readDouble()));
            }
            end(in);
            var result = new WorkerRequest(runId, proposal, approvalId, baselineId, baselineVersion,
                    baselineDescriptor, baselineManifest, baselineBytes, candidateId, candidateVersion,
                    candidateDescriptor, candidateManifest, candidateBytes, datasetManifest, dataset, rules);
            verifyWorkerRequest(result);
            return result;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("invalid typed shadow worker request", e);
        }
    }

    /** Encodes a typed request inside the outer transport envelope. */
    public static byte[] encodeRequestFrame(java.util.UUID requestId,
            ShadowEvaluationExecutionBoundary.ExecutionRequest request,
            ShadowEvaluationManifest.DatasetManifest datasetManifest,
            byte[] baselineArtifact, byte[] candidateArtifact) throws IOException {
        byte[] payload = encodeRequest(request, datasetManifest, baselineArtifact, candidateArtifact);
        return ShadowEvaluationWorkerProtocol.encode(new ShadowEvaluationWorkerProtocol.Frame(
                requestId, ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_REQUEST, payload));
    }

    /** Decodes only a correlated successful response frame. Failure frames use decodeFailureFrame. */
    public static ShadowEvaluationReport decodeResponseFrame(byte[] frame, java.util.UUID expectedRequestId)
            throws IOException {
        var decoded = ShadowEvaluationWorkerProtocol.decode(frame);
        if (!decoded.requestId().equals(expectedRequestId)
                || decoded.type() != ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_RESPONSE) {
            throw new IOException("unexpected or uncorrelated shadow worker response frame");
        }
        return decodeResponse(decoded.payload());
    }

    public static WorkerFailure decodeFailureFrame(byte[] frame, java.util.UUID expectedRequestId)
            throws IOException {
        var decoded = ShadowEvaluationWorkerProtocol.decode(frame);
        if (!decoded.requestId().equals(expectedRequestId)
                || decoded.type() != ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_FAILURE) {
            throw new IOException("unexpected or uncorrelated shadow worker failure frame");
        }
        return decodeFailure(decoded.payload());
    }

    public static byte[] encodeResponse(ShadowEvaluationReport report) throws IOException {
        Objects.requireNonNull(report, "report");
        return write(out -> {
            header(out, 2);
            string(out, report.runId());
            string(out, report.proposalId());
            out.writeByte(report.subject().kind().ordinal());
            string(out, report.subject().id());
            string(out, report.sourceSnapshotId());
            string(out, report.approvalId());
            string(out, report.policyFingerprint());
            string(out, report.baselineEvaluatorId());
            string(out, report.baselineEvaluatorVersion());
            string(out, report.baselineEvaluatorConfigFingerprint());
            string(out, report.candidateEvaluatorId());
            string(out, report.candidateEvaluatorVersion());
            string(out, report.candidateEvaluatorConfigFingerprint());
            string(out, report.baselineCandidateId());
            string(out, report.baselineCandidateVersion());
            string(out, report.candidateId());
            string(out, report.candidateVersion());
            string(out, report.datasetId());
            string(out, report.datasetVersion());
            string(out, report.datasetFingerprint());
            out.writeByte(report.status().ordinal());
            out.writeInt(report.metrics().size());
            for (var metric : report.metrics()) {
                string(out, metric.name());
                out.writeDouble(metric.baselineValue());
                out.writeDouble(metric.candidateValue());
                out.writeByte(metric.direction().ordinal());
                out.writeDouble(metric.requiredImprovement());
            }
            out.writeInt(report.evidenceReferences().size());
            for (String evidence : report.evidenceReferences()) string(out, evidence);
            out.writeLong(report.evaluatedAt().toEpochMilli());
        });
    }

    public static ShadowEvaluationReport decodeResponse(byte[] payload) throws IOException {
        try (DataInputStream in = input(payload)) {
            header(in, 2);
            String runId = string(in);
            String proposalId = string(in);
            var kind = enumValue(FeedbackAggregationRecoveryService.SubjectKind.values(), in.readUnsignedByte(), "subject kind");
            var subject = new FeedbackAggregationRecoveryService.Subject(kind, string(in));
            String snapshot = string(in);
            String approval = string(in);
            String policy = string(in);
            String baselineEvaluator = string(in);
            String baselineEvaluatorVersion = string(in);
            String baselineConfig = string(in);
            String candidateEvaluator = string(in);
            String candidateEvaluatorVersion = string(in);
            String candidateConfig = string(in);
            String baselineCandidate = string(in);
            String baselineVersion = string(in);
            String candidate = string(in);
            String candidateVersion = string(in);
            String dataset = string(in);
            String datasetVersion = string(in);
            String fingerprint = string(in);
            var status = enumValue(ShadowEvaluationReport.Status.values(), in.readUnsignedByte(), "report status");
            int metricCount = count(in, MAX_METRICS);
            List<ShadowEvaluationReport.Metric> metrics = new ArrayList<>(metricCount);
            for (int i = 0; i < metricCount; i++) {
                metrics.add(new ShadowEvaluationReport.Metric(string(in), in.readDouble(), in.readDouble(),
                        enumValue(ShadowEvaluationReport.Direction.values(), in.readUnsignedByte(), "metric direction"),
                        in.readDouble()));
            }
            int evidenceCount = count(in, MAX_EVIDENCE);
            List<String> evidence = new ArrayList<>(evidenceCount);
            for (int i = 0; i < evidenceCount; i++) evidence.add(string(in));
            Instant evaluatedAt = Instant.ofEpochMilli(in.readLong());
            end(in);
            return new ShadowEvaluationReport(runId, proposalId, subject, snapshot, approval, policy,
                    baselineEvaluator, baselineEvaluatorVersion, baselineConfig, candidateEvaluator,
                    candidateEvaluatorVersion, candidateConfig, baselineCandidate, baselineVersion,
                    candidate, candidateVersion, dataset, datasetVersion, fingerprint, status,
                    metrics, evidence, evaluatedAt);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("invalid typed shadow worker response", e);
        }
    }

    public static byte[] encodeFailure(WorkerFailure failure) throws IOException {
        return write(out -> { header(out, 3); string(out, failure.code()); string(out, failure.message()); });
    }

    public static WorkerFailure decodeFailure(byte[] payload) throws IOException {
        try (DataInputStream in = input(payload)) {
            header(in, 3);
            WorkerFailure failure = new WorkerFailure(string(in), string(in));
            end(in);
            return failure;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("invalid typed shadow worker failure", e);
        }
    }

    /** Worker-side mandatory digest check; call before passing bytes to any classloader. */
    public static void verifyArtifactBytes(ShadowEvaluationManifest.EvaluatorArtifactManifest manifest,
                                           byte[] artifact) throws IOException {
        if (manifest == null || artifact == null || artifact.length == 0 || artifact.length > MAX_ARTIFACT_BYTES) {
            throw new IOException("invalid evaluator artifact payload");
        }
        if (!manifest.artifactDigest().equals(sha256(artifact))) {
            throw new IOException("evaluator artifact digest mismatch");
        }
    }

    private static void verifyRequestBindings(ShadowEvaluationExecutionBoundary.ExecutionRequest r,
            ShadowEvaluationManifest.DatasetManifest dm, byte[] baseline, byte[] candidate) throws IOException {
        if (baseline == null || candidate == null || baseline.length == 0 || candidate.length == 0) {
            throw new IOException("both evaluator artifact byte arrays are required");
        }
        if (r.dataset().cases().size() != dm.caseCount()
                || !r.dataset().id().equals(dm.datasetId()) || !r.dataset().version().equals(dm.datasetVersion())
                || !ShadowEvaluationRunner.fingerprint(r.dataset()).equals(dm.contentFingerprint())) {
            throw new IOException("dataset does not match its signed manifest");
        }
        bindEvaluator(r.baselineCandidateId(), r.baselineCandidateVersion(), r.baselineDescriptor(),
                r.baselineArtifact(), baseline);
        bindEvaluator(r.candidateId(), r.candidateVersion(), r.candidateDescriptor(),
                r.candidateArtifact(), candidate);
    }

    private static void verifyWorkerRequest(WorkerRequest r) throws IOException {
        if (r.dataset().cases().size() != r.datasetManifest().caseCount()
                || !r.dataset().id().equals(r.datasetManifest().datasetId())
                || !r.dataset().version().equals(r.datasetManifest().datasetVersion())
                || !ShadowEvaluationRunner.fingerprint(r.dataset()).equals(r.datasetManifest().contentFingerprint())) {
            throw new IOException("worker dataset does not match signed manifest fingerprint");
        }
        bindEvaluator(r.baselineCandidateId(), r.baselineCandidateVersion(), r.baselineDescriptor(),
                r.baselineManifest(), r.baselineArtifact());
        bindEvaluator(r.candidateId(), r.candidateVersion(), r.candidateDescriptor(),
                r.candidateManifest(), r.candidateArtifact());
    }

    private static void bindEvaluator(String candidateId, String candidateVersion,
            Evaluator.EvaluatorDescriptor descriptor,
            ShadowEvaluationManifest.EvaluatorArtifactManifest manifest, byte[] bytes) throws IOException {
        if (!candidateId.equals(manifest.candidateId()) || !candidateVersion.equals(manifest.candidateVersion())
                || !descriptor.id().equals(manifest.evaluatorId())
                || !descriptor.version().equals(manifest.evaluatorVersion())
                || !descriptor.configurationFingerprint().equals(manifest.configurationFingerprint())) {
            throw new IOException("evaluator descriptor does not match signed artifact manifest");
        }
        verifyArtifactBytes(manifest, bytes);
    }

    private interface Writer { void write(DataOutputStream out) throws IOException; }
    private static byte[] write(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(out);
        }
        byte[] result = bytes.toByteArray();
        if (result.length > ShadowEvaluationWorkerProtocol.MAX_PAYLOAD_BYTES) {
            throw new IOException("typed worker payload exceeds protocol limit");
        }
        return result;
    }
    private static DataInputStream input(byte[] payload) throws IOException {
        if (payload == null || payload.length > ShadowEvaluationWorkerProtocol.MAX_PAYLOAD_BYTES) {
            throw new IOException("invalid typed worker payload size");
        }
        return new DataInputStream(new ByteArrayInputStream(payload));
    }
    private static void header(DataOutputStream out, int type) throws IOException {
        out.writeInt(PAYLOAD_MAGIC); out.writeShort(PAYLOAD_VERSION); out.writeByte(type);
    }
    private static void header(DataInputStream in, int expectedType) throws IOException {
        if (in.readInt() != PAYLOAD_MAGIC || in.readShort() != PAYLOAD_VERSION
                || in.readUnsignedByte() != expectedType) throw new IOException("unsupported typed worker payload header");
    }
    private static void end(DataInputStream in) throws IOException {
        if (in.read() != -1) throw new IOException("trailing bytes in typed worker payload");
    }
    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = required(value, MAX_STRING_BYTES, "string").getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String string(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > MAX_STRING_BYTES) throw new IOException("invalid worker string length");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new IOException("truncated worker string");
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("invalid UTF-8 in worker string");
        }
        return value;
    }
    private static void bytes(DataOutputStream out, byte[] value, int max) throws IOException {
        if (value == null || value.length == 0 || value.length > max) throw new IOException("invalid worker artifact length");
        out.writeInt(value.length); out.write(value);
    }
    private static byte[] bytes(DataInputStream in, int max) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > max) throw new IOException("invalid worker artifact length");
        byte[] value = in.readNBytes(length);
        if (value.length != length) throw new IOException("truncated worker artifact");
        return value;
    }
    private static void descriptor(DataOutputStream out, Evaluator.EvaluatorDescriptor d) throws IOException {
        string(out, d.id()); string(out, d.version()); stringMap(out, d.configuration());
    }
    private static Evaluator.EvaluatorDescriptor descriptor(DataInputStream in) throws IOException {
        return new Evaluator.EvaluatorDescriptor(string(in), string(in), stringMap(in));
    }
    private static void manifest(DataOutputStream out, ShadowEvaluationManifest.EvaluatorArtifactManifest m) throws IOException {
        string(out, m.artifactId()); string(out, m.artifactVersion()); string(out, m.artifactReference());
        string(out, m.artifactDigest()); string(out, m.candidateId()); string(out, m.candidateVersion());
        string(out, m.evaluatorId()); string(out, m.evaluatorVersion()); string(out, m.configurationFingerprint());
        string(out, m.signerId()); string(out, m.signature());
    }
    private static ShadowEvaluationManifest.EvaluatorArtifactManifest manifest(DataInputStream in) throws IOException {
        return new ShadowEvaluationManifest.EvaluatorArtifactManifest(string(in), string(in), string(in),
                string(in), string(in), string(in), string(in), string(in), string(in), string(in), string(in));
    }
    private static void datasetManifest(DataOutputStream out, ShadowEvaluationManifest.DatasetManifest m) throws IOException {
        string(out, m.datasetId()); string(out, m.datasetVersion()); string(out, m.schemaVersion());
        out.writeInt(m.caseCount()); string(out, m.contentFingerprint()); string(out, m.sourceReference());
        string(out, m.signerId()); string(out, m.signature());
    }
    private static ShadowEvaluationManifest.DatasetManifest datasetManifest(DataInputStream in) throws IOException {
        return new ShadowEvaluationManifest.DatasetManifest(string(in), string(in), string(in),
                in.readInt(), string(in), string(in), string(in), string(in));
    }
    private static void dataset(DataOutputStream out, Evaluator.Dataset d) throws IOException {
        string(out, d.id()); string(out, d.version());
        if (d.cases().size() > MAX_CASES) throw new IOException("too many evaluation cases");
        out.writeInt(d.cases().size());
        for (var item : d.cases()) {
            string(out, item.id()); string(out, item.input());
            out.writeBoolean(item.expectedOutput() != null);
            if (item.expectedOutput() != null) string(out, item.expectedOutput());
            stringMap(out, item.metadata());
        }
    }
    private static Evaluator.Dataset dataset(DataInputStream in) throws IOException {
        String id = string(in); String version = string(in);
        int count = count(in, MAX_CASES);
        if (count == 0) throw new IOException("empty worker dataset");
        List<Evaluator.EvaluationCase> cases = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String caseId = string(in); String input = string(in);
            String expected = in.readBoolean() ? string(in) : null;
            cases.add(new Evaluator.EvaluationCase(caseId, input, expected, stringMap(in)));
        }
        return new Evaluator.Dataset(id, version, cases);
    }
    private static void stringMap(DataOutputStream out, Map<String, String> map) throws IOException {
        if (map.size() > MAX_METADATA) throw new IOException("worker map has too many entries");
        TreeMap<String, String> sorted = new TreeMap<>(map);
        out.writeInt(sorted.size());
        for (var entry : sorted.entrySet()) { string(out, entry.getKey()); string(out, entry.getValue()); }
    }
    private static Map<String, String> stringMap(DataInputStream in) throws IOException {
        int count = count(in, MAX_METADATA);
        Map<String, String> map = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            String key = string(in); String value = string(in);
            if (map.putIfAbsent(key, value) != null) throw new IOException("duplicate worker map key");
        }
        return Map.copyOf(map);
    }
    private static int count(DataInputStream in, int max) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > max) throw new IOException("invalid worker collection size");
        return count;
    }
    private static <E> E enumValue(E[] values, int ordinal, String name) throws IOException {
        if (ordinal < 0 || ordinal >= values.length) throw new IOException("invalid " + name);
        return values[ordinal];
    }
    private static String required(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("invalid " + field);
        return value;
    }
    private static String sha256(byte[] value) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
    }
}
