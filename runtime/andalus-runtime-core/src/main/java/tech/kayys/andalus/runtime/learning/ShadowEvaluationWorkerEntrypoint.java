package tech.kayys.andalus.runtime.learning;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
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
        int exitCode;
        try {
            exitCode = run(args, System.in, System.out, System.err, uniqueProvider());
        } catch (Exception failure) {
            reportFailure(System.err, failure);
            exitCode = 2;
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    /**
     * Executes one request. Package visibility and explicit streams make the process contract
     * testable without replacing JVM-global stdin/stdout or terminating the test process.
     */
    static int run(String[] args, InputStream input, OutputStream output, PrintStream error,
            BootstrapProvider provider) {
        try {
            if (args == null || args.length != 0)
                throw new IllegalArgumentException("worker accepts no command-line arguments");
            if (input == null || output == null || error == null || provider == null)
                throw new IllegalArgumentException("worker process dependencies are required");
            byte[] request = readBounded(input, MAX_FRAME_BYTES);
            if (request.length == 0) throw new IOException("empty worker request");
            ShadowEvaluationWorkerEngine engine = provider.createEngine();
            if (engine == null) throw new IOException("worker bootstrap returned no engine");
            byte[] response = await(engine.handle(request));
            if (response == null || response.length == 0 || response.length > MAX_FRAME_BYTES)
                throw new IOException("invalid worker response size");
            ShadowEvaluationWorkerProtocol.Frame responseFrame = ShadowEvaluationWorkerProtocol.decode(response);
            ShadowEvaluationWorkerProtocol.Frame requestFrame = ShadowEvaluationWorkerProtocol.decode(request);
            if (!responseFrame.requestId().equals(requestFrame.requestId())
                    || (responseFrame.type() != ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_RESPONSE
                    && responseFrame.type() != ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_FAILURE))
                throw new IOException("worker response correlation or type mismatch");
            output.write(response);
            output.flush();
            return 0;
        } catch (Exception failure) {
            reportFailure(error, failure);
            return 2;
        }
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

    private static void reportFailure(PrintStream error, Throwable failure) {
        try {
            error.println("shadow-worker: request failed (" + safeCode(failure) + ")");
            error.flush();
        } catch (RuntimeException ignored) { }
    }

    private static String safeCode(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        if (cause instanceof IOException) return "IO";
        if (cause instanceof IllegalArgumentException) return "INVALID_REQUEST";
        return "EXECUTION";
    }
}
