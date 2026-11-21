package tech.kayys.andalus.runtime.learning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Child-process fixture launched by DockerShadowEvaluationWorkerTransportProcessTest. */
public final class DockerShadowEvaluationTransportFixture {
    private DockerShadowEvaluationTransportFixture() { }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "valid" : args[args.length - 1];
        if ("timeout".equals(mode)) {
            Thread.sleep(30_000);
            return;
        }
        if ("malformed".equals(mode)) {
            System.out.write("not-a-worker-frame".getBytes(StandardCharsets.UTF_8));
            System.out.flush();
            return;
        }
        if ("nonzero".equals(mode)) System.exit(7);

        byte[] request = System.in.readAllBytes();
        var decoded = ShadowEvaluationWorkerProtocol.decode(request);
        if (decoded.type() != ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_REQUEST) {
            System.exit(8);
            return;
        }
        byte[] failure = ShadowEvaluationWorkerCodec.encodeFailure(
                new ShadowEvaluationWorkerCodec.WorkerFailure(
                        "FIXTURE_FAILURE", "synthetic worker response"));
        byte[] response = ShadowEvaluationWorkerProtocol.encode(
                new ShadowEvaluationWorkerProtocol.Frame(decoded.requestId(),
                        ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_FAILURE, failure));
        System.out.write(response);
        System.out.flush();
    }
}
