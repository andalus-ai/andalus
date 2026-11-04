package tech.kayys.andalus.sandbox.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.kayys.andalus.spi.sandbox.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProcessSandboxProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void testProcessSandboxExecutionAndLifecycle() throws Exception {
        ProcessSandboxProvider provider = new ProcessSandboxProvider(tempDir);
        assertEquals(ProcessSandboxProvider.ID, provider.getProviderId());
        SandboxRequest req = request("sb-proc-1", 1024L);
        Sandbox sandbox = provider.create(req);
        assertInstanceOf(ProcessSandbox.class, sandbox);
        ProcessSandbox processSandbox = (ProcessSandbox) sandbox;

        assertEquals(SandboxState.CREATED, processSandbox.state());
        processSandbox.start();
        assertEquals(SandboxState.RUNNING, processSandbox.state());
        assertEquals("tenant-alpha", processSandbox.context().tenantId().orElse(""));

        ProcessExecutionResult result = processSandbox.execute(new ProcessExecutionRequest(
                List.of("echo", "HELLO"), Map.of(), Duration.ofSeconds(5), false)).join();
        assertTrue(result.successful());
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("HELLO"));

        processSandbox.destroy();
        assertEquals(SandboxState.DESTROYED, processSandbox.state());
    }

    @Test
    void rejectsSandboxIdsThatEscapeTheBaseDirectory() {
        ProcessSandboxProvider provider = new ProcessSandboxProvider(tempDir);
        assertThrows(IllegalArgumentException.class, () -> provider.create(request("../outside", 1024L)));
        assertFalse(Files.exists(tempDir.getParent().resolve("outside")));
    }

    @Test
    void enforcesOneCombinedOutputBudgetAcrossBothStreams() throws Exception {
        ProcessSandboxProvider provider = new ProcessSandboxProvider(tempDir);
        ProcessSandbox sandbox = (ProcessSandbox) provider.create(request("output-budget", 64L));
        sandbox.start();
        try {
            ProcessExecutionResult result = sandbox.execute(new ProcessExecutionRequest(
                    List.of("sh", "-c", "head -c 128 /dev/zero; head -c 128 /dev/zero >&2"),
                    Map.of(), Duration.ofSeconds(5), false)).join();
            assertTrue(result.outputLimitExceeded());
            assertTrue(result.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    + result.stderr().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 64);
        } finally {
            sandbox.destroy();
        }
    }

    private SandboxRequest request(String id, long outputLimit) {
        return new SandboxRequest(id, SandboxType.PROCESS, Set.of("process-isolation"),
                new SandboxLimits(-1L, -1L, -1L, -1L, Duration.ofSeconds(10),
                        outputLimit, -1L),
                SandboxFilesystem.empty(), SandboxNetwork.disabled(),
                Map.of("MY_TEST_VAR", "WORLD"), Map.of("tenantId", "tenant-alpha"));
    }
}