package tech.kayys.andalus.runtime.learning;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Versioned, domain-separated canonical bytes used by manifest signers and verifiers. */
public final class ShadowEvaluationSigning {
    private ShadowEvaluationSigning() { }

    public static byte[] datasetPayload(ShadowEvaluationManifest.DatasetManifest m) {
        return encode("ANDALUS-SHADOW-DATASET-MANIFEST-V1", out -> {
            field(out, m.datasetId()); field(out, m.datasetVersion()); field(out, m.schemaVersion());
            out.writeInt(m.caseCount()); field(out, m.contentFingerprint());
            field(out, m.sourceReference()); field(out, m.signerId());
        });
    }

    public static byte[] evaluatorPayload(ShadowEvaluationManifest.EvaluatorArtifactManifest m) {
        return encode("ANDALUS-SHADOW-EVALUATOR-MANIFEST-V1", out -> {
            field(out, m.artifactId()); field(out, m.artifactVersion()); field(out, m.artifactReference());
            field(out, m.artifactDigest()); field(out, m.candidateId()); field(out, m.candidateVersion());
            field(out, m.evaluatorId()); field(out, m.evaluatorVersion());
            field(out, m.configurationFingerprint()); field(out, m.signerId());
        });
    }

    private static byte[] encode(String domain, Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            field(out, domain); writer.write(out); out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException("Unable to encode manifest", impossible); }
    }

    private static void field(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }

    @FunctionalInterface private interface Writer { void write(DataOutputStream out) throws IOException; }
}
