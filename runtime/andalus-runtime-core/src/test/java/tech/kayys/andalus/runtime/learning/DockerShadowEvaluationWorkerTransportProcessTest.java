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
 * framing, correlation, bounded response parsing, non-zero exits, and process-tree timeout cleanup
 * without requiring a Docker daemon or claiming container-isolation validation.
 */
class DockerShadowEvaluationWorkerTransportProcessTest {
    private static final String PINNED_IMAGE =
            "registry.example.test/andalus/shadow-worker@sha256:" + "b".repeat(64);
    private static final String FAKE_CONTAINER_ID = "c".repeat(64);

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
        var transport = transport(fakeExecutable("malformed"), Duration.ofSeconds(5));

        assertThatThrownBy(() -> transport.exchange(request(UUID.randomUUID())))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsTruncatedWorkerFrame() throws Exception {
        var transport = transport(fakeExecutable("truncated"), Duration.ofSeconds(5));

        assertThatThrownBy(() -> transport.exchange(request(UUID.randomUUID())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void terminatesWorkerThatExceedsConfiguredTimeout() throws Exception {
        var transport = transport(fakeExecutable("timeout"), Duration.ofMillis(150));

        assertThatThrownBy(() -> transport.exchange(request(UUID.randomUUID())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void terminatesDescendantProcessesWhenWorkerTimesOut() throws Exception {
        Path childPidFile = temporaryDirectory.resolve("worker-child.pid");
        var transport = transport(fakeExecutable("tree-timeout", childPidFile), Duration.ofMillis(500));

        assertThatThrownBy(() -> transport.exchange(request(UUID.randomUUID())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timeout");

        assertThat(childPidFile).exists();
        assertThat(Files.readString(cleanupLog())).contains("rm --force " + FAKE_CONTAINER_ID);
        long childPid = Long.parseLong(Files.readString(childPidFile).trim());
        for (int attempt = 0; attempt < 20 && ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false);
             attempt++) {
            Thread.sleep(50);
        }
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void rejectsNonZeroWorkerExit() throws Exception {
        var transport = transport(fakeExecutable("nonzero"), Duration.ofSeconds(5));

        assertThatThrownBy(() -> transport.exchange(request(UUID.randomUUID())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("status 7");
    }

    @Test
    void rejectsResponseWithDifferentRequestCorrelationId() throws Exception {
        var transport = transport(fakeExecutable("wrong-correlation"), Duration.ofSeconds(5));

        assertThatThrownBy(() -> transport.exchange(request(UUID.randomUUID())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("request ID mismatch");
    }

    @Test
    void rejectsStdoutLargerThanMaximumWorkerFrame() throws Exception {
        var transport = transport(fakeExecutable("stdout-oversize"), Duration.ofSeconds(5));

        assertThatThrownBy(() -> transport.exchange(request(UUID.randomUUID())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("stream exceeded");
    }

    @Test
    void rejectsStderrLargerThanConfiguredDiagnosticLimit() throws Exception {
        var transport = transport(fakeExecutable("stderr-oversize"), Duration.ofSeconds(5));

        assertThatThrownBy(() -> transport.exchange(request(UUID.randomUUID())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("stream exceeded");
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
        return fakeExecutable(mode, null);
    }

    private Path fakeExecutable(String mode, Path childPidFile) throws Exception {
        Path executable = temporaryDirectory.resolve("fake-docker-" + mode);
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String fixtureClasses = Path.of(DockerShadowEvaluationTransportFixture.class
                .getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String productionClasses = Path.of(ShadowEvaluationWorkerProtocol.class
                .getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String classpath = String.join(java.io.File.pathSeparator,
                fixtureClasses, productionClasses, System.getProperty("java.class.path"));
        String environment = childPidFile == null ? "" :
                "WORKER_CHILD_PID_FILE=" + shellQuote(childPidFile.toString()) + " ";
        String script = "#!/bin/sh\n" + environment + "exec " + shellQuote(javaExecutable)
                + " -cp " + shellQuote(classpath)
                + " tech.kayys.andalus.runtime.learning.DockerShadowEvaluationTransportFixture "
                + "\"$@\" " + shellQuote(mode) + "\n";
        Files.writeString(executable, script, StandardCharsets.UTF_8);
        if (!executable.toFile().setExecutable(true, true)) {
            throw new IOException("could not mark fake Docker executable as executable");
        }
        return executable;
    }

    private Path cleanupLog() {
        return temporaryDirectory.resolve("docker-cleanup.log");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
