package tech.kayys.andalus.runtime.learning;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Verifies the worker's binary stdout response; emits no protocol bytes of its own. */
public final class WorkerSmokeResponseVerifier {
    private static final UUID REQUEST_ID =
            UUID.fromString("4d7b8b2a-8ec9-4f1d-a7c3-6efb86e6a9c1");

    private WorkerSmokeResponseVerifier() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("response file required");
        byte[] response = Files.readAllBytes(Path.of(args[0]));
        var failure = ShadowEvaluationWorkerCodec.decodeFailureFrame(response, REQUEST_ID);
        if (!"INVALID_REQUEST".equals(failure.code())) {
            throw new IllegalStateException("unexpected worker failure code");
        }
        System.out.println("worker-container-smoke: protocol response verified");
    }
}
