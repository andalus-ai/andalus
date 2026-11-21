package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ShadowEvaluationWorkerEntrypointTest {

    @Test
    void writesOnlyCorrelatedProtocolFailureToStdoutForRejectedRequest() throws Exception {
        UUID requestId = UUID.randomUUID();
        byte[] request = frame(requestId, new byte[]{1, 2, 3, 4});
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = ShadowEvaluationWorkerEntrypoint.run(new String[0],
                new ByteArrayInputStream(request), stdout, error(stderr), provider());

        var failure = ShadowEvaluationWorkerCodec.decodeFailureFrame(stdout.toByteArray(), requestId);
        assertThat(exit).isZero();
        assertThat(failure.code()).isEqualTo("INVALID_REQUEST");
        assertThat(failure.message()).isEqualTo("Worker request payload is invalid");
        assertThat(stderr.size()).isZero();
    }

    @Test
    void rejectsEmptyInputWithoutWritingProtocolBytes() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = ShadowEvaluationWorkerEntrypoint.run(new String[0],
                new ByteArrayInputStream(new byte[0]), stdout, error(stderr), provider());

        assertThat(exit).isEqualTo(2);
        assertThat(stdout.size()).isZero();
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("request failed (IO)");
    }

    @Test
    void rejectsMalformedOuterFrameAndKeepsStdoutClean() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = ShadowEvaluationWorkerEntrypoint.run(new String[0],
                new ByteArrayInputStream(new byte[]{9, 8, 7}), stdout, error(stderr), provider());

        assertThat(exit).isEqualTo(2);
        assertThat(stdout).isEmpty();
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("request failed (INVALID_REQUEST)");
    }

    @Test
    void rejectsCommandLineArgumentsBeforeReadingRequest() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = ShadowEvaluationWorkerEntrypoint.run(new String[]{"--debug"},
                new ByteArrayInputStream(new byte[]{1}), stdout, error(stderr), provider());

        assertThat(exit).isEqualTo(2);
        assertThat(stdout).isEmpty();
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("request failed (INVALID_REQUEST)");
    }

    @Test
    void boundedReaderRejectsOversizedInput() {
        byte[] input = new byte[33];
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                ShadowEvaluationWorkerEntrypoint.readBounded(new ByteArrayInputStream(input), 32)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds frame limit");
    }

    private static byte[] frame(UUID requestId, byte[] payload) throws IOException {
        return ShadowEvaluationWorkerProtocol.encode(new ShadowEvaluationWorkerProtocol.Frame(
                requestId, ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_REQUEST, payload));
    }

    private static ShadowEvaluationWorkerEntrypoint.BootstrapProvider provider() {
        return () -> new ShadowEvaluationWorkerEngine(
                new ShadowEvaluationWorkerManifestVerifier(id -> Optional.empty()),
                (bytes, descriptor) -> { throw new AssertionError("untrusted artifact must never load"); },
                new ShadowEvaluationRunner());
    }

    private static PrintStream error(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }
}
