package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Process-level transport tests using a local fake Docker executable. These exercise stdin/stdout
 * framing, correlation, bounded response parsing, non-zero process exits, and timeout termination
 * without requiring a Docker daemon or claiming container-isolation validation.
 */
class DockerShadowEvaluationWorkerTransportProcessTest {
    private static final String PINNED_IMAGE =
            "registry.example.test/andalus/shadow-worker@sha256:" + "b".repeat(64);

    @TempDir Path temporaryDirectory;

    @Test
    void exchangesCorrelatedWorkerFailureFrameThroughChildProcess() throws Exception {
        Path executable = fakeExecutable("valid");
        UUID requestId = UUID.randomUUID();
        var transport = transport(executable, Duration.ofSeconds(5));

        byte[] response = transport.exchange(request(requestId));
        var failure = ShadowEvaluationWorkerCodec.decodeFailureFrame(response, requestId);

        assertThat(failure.code()).isEqualTo("FIXTURE_FAILURE");
        assertThat(failure.message()).isEqualTo("synthetic worker response");
    }

    @Test
    void rejectsMalformedWorkerStdout() throws Exception {
        Path executable = fakeExecutable("malformed");
        var transport = transport(executable, Duration.ofSeconds(5));

        assertThatThrownBy(() -> transport.exchange(request(UUID.randomUUID())))
                .isInstanceOf(IOException.class);
    }

    @Test
    void terminatesWorkerThatExceedsConfiguredTimeout() throws Exception {
        Path executable = fakeExecutable("timeout");
        var transport = transport(executable, Duration.ofMillis(150));

        assertThatThrownBy(() -> transport.exchange(request(UUID.randomUUID())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void rejectsNonZeroWorkerExit() throws Exception {
        Path executable = fakeExecutable("nonzero");
        var transport = transport(executable, Duration.ofSeconds(5));

        assertThatThrownBy(() -> transport.exchange(request(UUID.randomUUID())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("status 7");
    }
    @Test
    void rejectsResponseWithDifferentRequestCorrelationId() throws Exception {
        Path executable = fakeExecutable("wrong-correlation");
        var transport = transport(executable, Duration.ofSeconds(5));

        assertThatThrownBy(() -> transport.exchange(request(UUID.randomUUID())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("request ID mismatch");
    }
    private DockerShadowEvaluationWorkerTransport transport(Path executable, Duration timeout) {
        return new DockerShadowEvaluationWorkerTransport(executable.toString(), PINNED_IMAGE,
                new DockerShadowEvaluationWorkerTransport.Limits(
                        256, 1.0, 32, 16, timeout, 4096));
    }

    private byte[] request(UUID requestId) {
        return ShadowEvaluationWorkerProtocol.encode(new ShadowEvaluationWorkerProtocol.Frame(
                requestId, ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_REQUEST,
                new byte[]{1, 2, 3}));
    }

    private Path fakeExecutable(String mode) throws Exception {
        Path executable = temporaryDirectory.resolve("fake-docker");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String fixtureClasses = Path.of(DockerShadowEvaluationTransportFixture.class
                .getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String productionClasses = Path.of(ShadowEvaluationWorkerProtocol.class
                .getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String classpath = String.join(java.io.File.pathSeparator,
                fixtureClasses, productionClasses, System.getProperty("java.class.path"));
        String script = "#!/bin/sh\nexec " + shellQuote(javaExecutable) + " -cp " + shellQuote(classpath)
                + " tech.kayys.andalus.runtime.learning.DockerShadowEvaluationTransportFixture "
                + "\"$@\" " + shellQuote(mode) + "\n";
        Files.writeString(executable, script, StandardCharsets.UTF_8);
        if (!executable.toFile().setExecutable(true, true)) {
            throw new IOException("could not mark fake Docker executable as executable");
        }
        return executable;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
