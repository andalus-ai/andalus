package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerShadowEvaluationWorkerTransportTest {
    private static final String PINNED_IMAGE =
            "registry.example.test/andalus/shadow-worker@sha256:" + "a".repeat(64);

    @Test
    void requiresDigestPinnedImageAndValidResourceLimits() {
        var limits = DockerShadowEvaluationWorkerTransport.Limits.conservativeDefaults();
        assertThatThrownBy(() -> new DockerShadowEvaluationWorkerTransport(
                "/usr/bin/docker", "registry.example.test/andalus/shadow-worker:latest", limits))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sha256");
        assertThatThrownBy(() -> new DockerShadowEvaluationWorkerTransport(
                "/usr/bin/docker", PINNED_IMAGE,
                new DockerShadowEvaluationWorkerTransport.Limits(
                        1, 1.0, 128, 64, Duration.ofMinutes(1), 1024)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildsHardenedNoMountNoNetworkContainerCommand() {
        var transport = new DockerShadowEvaluationWorkerTransport(
                "/usr/bin/docker", PINNED_IMAGE,
                DockerShadowEvaluationWorkerTransport.Limits.conservativeDefaults());

        assertThat(transport.command()).contains(
                "run", "--rm", "--interactive", "--pull=never", "--network=none",
                "--read-only", "--user=65532:65532", "--cap-drop=ALL",
                "--security-opt=no-new-privileges", "--log-driver=none", PINNED_IMAGE);
        assertThat(transport.command()).noneMatch(arg -> arg.startsWith("--mount")
                || arg.startsWith("-v") || arg.startsWith("--env"));
    }

    @Test
    void includesContainerIdFileForDaemonSideTimeoutCleanup() {
        var transport = new DockerShadowEvaluationWorkerTransport(
                "/usr/bin/docker", PINNED_IMAGE,
                DockerShadowEvaluationWorkerTransport.Limits.conservativeDefaults());

        assertThat(transport.command(java.nio.file.Path.of("/tmp/worker-container.id")))
                .contains("--cidfile", "/tmp/worker-container.id");
    }

    @Test
    void refusesNonRequestFramesBeforeStartingDocker() {
        var transport = new DockerShadowEvaluationWorkerTransport(
                "/usr/bin/docker", PINNED_IMAGE,
                DockerShadowEvaluationWorkerTransport.Limits.conservativeDefaults());
        byte[] response = ShadowEvaluationWorkerProtocol.encode(new ShadowEvaluationWorkerProtocol.Frame(
                java.util.UUID.randomUUID(),
                ShadowEvaluationWorkerProtocol.MessageType.EXECUTE_RESPONSE, new byte[0]));

        assertThatThrownBy(() -> transport.exchange(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("execute request");
    }
}
