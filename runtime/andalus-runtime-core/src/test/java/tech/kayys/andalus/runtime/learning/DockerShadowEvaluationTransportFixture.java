package tech.kayys.andalus.runtime.learning;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Child-process fixture launched by DockerShadowEvaluationWorkerTransportProcessTest. */
public final class DockerShadowEvaluationTransportFixture {
    private DockerShadowEvaluationTransportFixture() { }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "valid" : args[args.length - 1];
        byte[] request = System.in.readAllBytes();

        if ("timeout".equals(mode)) {
            Thread.sleep(30_000);
            return;
        }
        if ("tree-timeout".equals(mode)) {
            String marker = System.getenv("WORKER_CHILD_PID_FILE");
            if (marker == null || marker.isBlank()) throw new IllegalStateException("missing child PID file");
            new ProcessBuilder("/bin/sh", "-c", "echo $$ > " + shellQuote(marker) + "; exec sleep 30")
                    .start();
            Thread.sleep(30_000);
            return;
        }
        if ("malformed".equals(mode)) {
            System.out.write("not-a-worker-frame".getBytes(StandardCharsets.UTF_8));
            System.out.flush();
            return;
        }
        if ("truncated".equals(mode)) {
            byte[] partial = ShadowEvaluationWorkerProtocol.encode(new ShadowEvaluationWorkerProtocol.Frame(
                    UUID.randomUUID(), ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_FAILURE,
                    new byte[]{1, 2, 3}));
            System.out.write(partial, 0, 20);
            System.out.flush();
            return;
        }
        if ("stdout-oversize".equals(mode)) {
            System.out.write(new byte[ShadowEvaluationWorkerProtocol.MAX_FRAME_BYTES + 1]);
            System.out.flush();
            return;
        }
        if ("stderr-oversize".equals(mode)) {
            System.err.write(new byte[8193]);
            System.err.flush();
            return;
        }
        if ("nonzero".equals(mode)) System.exit(7);

        var decoded = ShadowEvaluationWorkerProtocol.decode(request);
        if (decoded.type() != ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_REQUEST) {
            System.exit(8);
            return;
        }
        byte[] failure = ShadowEvaluationWorkerCodec.encodeFailure(
                new ShadowEvaluationWorkerCodec.WorkerFailure(
                        "FIXTURE_FAILURE", "synthetic worker response"));
        UUID responseId = "wrong-correlation".equals(mode)
                ? UUID.randomUUID() : decoded.requestId();
        byte[] response = ShadowEvaluationWorkerProtocol.encode(
                new ShadowEvaluationWorkerProtocol.Frame(responseId,
                        ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_FAILURE, failure));
        System.out.write(response);
        System.out.flush();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
