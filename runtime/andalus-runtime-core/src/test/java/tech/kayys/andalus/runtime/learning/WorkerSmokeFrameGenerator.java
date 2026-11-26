package tech.kayys.andalus.runtime.learning;

import java.util.UUID;

/** Emits a deliberately invalid payload inside a valid outer frame for container smoke tests. */
public final class WorkerSmokeFrameGenerator {
    private WorkerSmokeFrameGenerator() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("no arguments expected");
        byte[] frame = ShadowEvaluationWorkerProtocol.encode(new ShadowEvaluationWorkerProtocol.Frame(
                UUID.fromString("4d7b8b2a-8ec9-4f1d-a7c3-6efb86e6a9c1"),
                ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_REQUEST,
                new byte[]{1, 2, 3, 4}));
        System.out.write(frame);
        System.out.flush();
    }
}
