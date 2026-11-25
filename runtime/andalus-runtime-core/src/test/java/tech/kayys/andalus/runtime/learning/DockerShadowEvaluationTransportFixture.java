package tech.kayys.andalus.runtime.learning;

import java.nio.charset.StandardCharsets;

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
        if ("malformed".equals(mode)) {
            System.out.write("not-a-worker-frame".getBytes(StandardCharsets.UTF_8));
            System.out.flush();
            return;
        }
        if ("stdout-oversize".equals(mode)) {
            System.out.write(new byte[ShadowEvaluationWorkerProtocol.MAX_PAYLOAD_BYTES + 65]);
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
        java.util.UUID responseId = "wrong-correlation".equals(mode)
                ? java.util.UUID.randomUUID() : decoded.requestId();
        byte[] response = ShadowEvaluationWorkerProtocol.encode(
                new ShadowEvaluationWorkerProtocol.Frame(responseId,
                        ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_FAILURE, failure));
        System.out.write(response);
        System.out.flush();
    }
}
