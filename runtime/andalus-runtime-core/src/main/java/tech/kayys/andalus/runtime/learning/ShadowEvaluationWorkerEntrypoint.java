package tech.kayys.andalus.runtime.learning;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.ServiceLoader;
import java.util.concurrent.CompletionStage;

/**
 * Single-request stdin/stdout process entrypoint for a digest-pinned shadow worker image.
 *
 * The provider and all of its dependencies are part of the immutable worker image. Evaluator
 * artifacts are not service providers: they are supplied as bytes in the request and are loaded
 * only by the engine after worker-local signature and digest verification.
 *
 * stdout is a binary protocol channel only. Implementations must send diagnostics to stderr.
 */
public final class ShadowEvaluationWorkerEntrypoint {
    private static final int MAX_FRAME_BYTES = ShadowEvaluationWorkerProtocol.MAX_PAYLOAD_BYTES + 64;

    /**
     * Trusted image-baked bootstrap. Implementations must build the verifier from a worker-local,
     * read-only trust bundle and provide a loader that accepts only verified artifact bytes.
     */
    public interface BootstrapProvider {
        ShadowEvaluationWorkerEngine createEngine() throws Exception;
    }

    private ShadowEvaluationWorkerEntrypoint() { }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            if (args.length != 0) throw new IllegalArgumentException("worker accepts no command-line arguments");
            BootstrapProvider provider = uniqueProvider();
            byte[] request = readBounded(System.in, MAX_FRAME_BYTES);
            if (request.length == 0) throw new IOException("empty worker request");
            byte[] response = await(provider.createEngine().handle(request));
            if (response == null || response.length == 0 || response.length > MAX_FRAME_BYTES)
                throw new IOException("invalid worker response size");
            ShadowEvaluationWorkerProtocol.Frame responseFrame = ShadowEvaluationWorkerProtocol.decode(response);
            ShadowEvaluationWorkerProtocol.Frame requestFrame = ShadowEvaluationWorkerProtocol.decode(request);
            if (!responseFrame.requestId().equals(requestFrame.requestId())
                    || (responseFrame.type() != ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_RESPONSE
                    && responseFrame.type() != ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_FAILURE))
                throw new IOException("worker response correlation or type mismatch");
            OutputStream output = System.out;
            output.write(response);
            output.flush();
        } catch (Throwable failure) {
            // Keep stdout uncontaminated and avoid leaking stack traces or request contents.
            try {
                System.err.println("shadow-worker: request failed (" + safeCode(failure) + ")");
            } catch (RuntimeException ignored) { }
            exitCode = 2;
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    private static BootstrapProvider uniqueProvider() {
        Iterator<BootstrapProvider> providers =
                ServiceLoader.load(BootstrapProvider.class, ShadowEvaluationWorkerEntrypoint.class.getClassLoader())
                        .iterator();
        if (!providers.hasNext()) throw new IllegalStateException("worker bootstrap provider missing");
        BootstrapProvider provider = providers.next();
        if (providers.hasNext()) throw new IllegalStateException("multiple worker bootstrap providers");
        return provider;
    }

    static byte[] readBounded(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() > maximum - count) throw new IOException("worker request exceeds frame limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static byte[] await(CompletionStage<byte[]> result) throws Exception {
        if (result == null) throw new IOException("worker engine returned no result");
        return result.toCompletableFuture().get();
    }

    private static String safeCode(Throwable failure) {
        if (failure instanceof IOException) return "IO";
        if (failure instanceof IllegalArgumentException) return "INVALID_REQUEST";
        return "EXECUTION";
    }
}
