package tech.kayys.andalus.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.kayys.andalus.operator.runtime.DefaultOperatorServiceRegistry;
import tech.kayys.andalus.operator.runtime.PermissiveOperatorAuthorization;
import tech.kayys.andalus.operator.runtime.sandbox.DefaultSandboxOperatorService;
import tech.kayys.andalus.sandbox.process.ProcessSandboxProvider;
import tech.kayys.andalus.sandbox.runtime.DefaultSandboxManager;
import tech.kayys.andalus.sandbox.runtime.DefaultSandboxProviderRegistry;
import tech.kayys.andalus.sandbox.runtime.observability.DefaultSandboxDiagnostics;
import tech.kayys.andalus.sandbox.runtime.observability.DefaultSandboxObservabilityManager;
import tech.kayys.andalus.spi.operator.OperatorContext;
import tech.kayys.andalus.spi.operator.OperatorResult;
import tech.kayys.andalus.spi.sandbox.*;
import tech.kayys.andalus.spi.sandbox.observability.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SandboxObservabilityAndOperatorTest {

    @TempDir
    Path tempDir;

    private DefaultSandboxManager sandboxManager;
    private DefaultSandboxObservabilityManager obsManager;
    private DefaultSandboxDiagnostics diagnostics;
    private DefaultSandboxOperatorService operatorService;

    @BeforeEach
    void setUp() throws Exception {
        DefaultSandboxProviderRegistry registry = new DefaultSandboxProviderRegistry();
        registry.register(new ProcessSandboxProvider(tempDir));
        sandboxManager = new DefaultSandboxManager(registry);
        obsManager = new DefaultSandboxObservabilityManager(null, sandboxManager);
        diagnostics = new DefaultSandboxDiagnostics(sandboxManager);
        operatorService = new DefaultSandboxOperatorService(sandboxManager, new PermissiveOperatorAuthorization());

        SandboxRequest request = new SandboxRequest(
                "sb-obs-1",
                SandboxType.PROCESS,
                Set.of(),
                SandboxLimits.unlimited(),
                SandboxFilesystem.empty(),
                SandboxNetwork.disabled(),
                Map.of(),
                Map.of("executionId", "exec-obs-1", "tenantId", "tenant-1")
        );
        sandboxManager.create(request);
        sandboxManager.start("sb-obs-1");
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Sandbox s : sandboxManager.list()) {
            try {
                sandboxManager.destroy(s.context().sandboxId());
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void testObservabilityRecordingAndHealth() {
        SandboxObservation obs = new SandboxObservation(
                "obs-1",
                SandboxObservationType.STARTED,
                Instant.now(),
                "sb-obs-1",
                "exec-obs-1",
                "tenant-1",
                "agent-1",
                "corr-1",
                "process",
                Map.of()
        );
        obsManager.record(obs);
        assertEquals(1, obsManager.getObservations().size());

        SandboxHealth health = obsManager.health("sb-obs-1").orElseThrow();
        assertEquals(SandboxHealthStatus.HEALTHY, health.status());
    }

    @Test
    void testDiagnosticsInspection() throws Exception {
        SandboxDiagnosticsSnapshot snapshot = diagnostics.inspect("sb-obs-1");
        assertNotNull(snapshot);
        assertEquals("sb-obs-1", snapshot.sandboxId());
        assertEquals(SandboxState.RUNNING, snapshot.state());
        assertEquals(SandboxHealthStatus.HEALTHY, snapshot.health());
        assertTrue(snapshot.supportedFeatures().contains(SandboxObservabilityFeature.LIFECYCLE_EVENTS));
    }

    @Test
    void testOperatorServiceControlPlane() {
        DefaultOperatorServiceRegistry registry = new DefaultOperatorServiceRegistry();
        registry.register(operatorService);
        assertTrue(registry.find(DefaultSandboxOperatorService.ID).isPresent());

        OperatorContext ctx = new OperatorContext("tenant-1", "admin", "corr-1", "req-1", Map.of());

        // List
        OperatorResult<List<tech.kayys.andalus.spi.operator.sandbox.SandboxSummary>> listResult = operatorService.list(ctx);
        assertTrue(listResult instanceof OperatorResult.Success);
        assertEquals(1, ((OperatorResult.Success<List<tech.kayys.andalus.spi.operator.sandbox.SandboxSummary>>) listResult).value().size());

        // Inspect
        OperatorResult<tech.kayys.andalus.spi.operator.sandbox.SandboxSummary> inspectResult = operatorService.inspect(ctx, "sb-obs-1");
        assertTrue(inspectResult instanceof OperatorResult.Success);
        assertEquals("sb-obs-1", ((OperatorResult.Success<tech.kayys.andalus.spi.operator.sandbox.SandboxSummary>) inspectResult).value().sandboxId());

        // Destroy via operator
        OperatorResult<Void> destroyResult = operatorService.destroy(ctx, "sb-obs-1");
        assertTrue(destroyResult instanceof OperatorResult.Success);
        assertFalse(sandboxManager.find("sb-obs-1").isPresent());
    }

    @Test
    void tenantCannotInspectOrMutateAnotherTenantsSandbox() {
        OperatorContext foreignTenant = new OperatorContext("tenant-2", "user-2", "corr-2", "req-2", Map.of());

        assertEquals("SANDBOX_NOT_FOUND", ((OperatorResult.Failure<?>) operatorService.inspect(foreignTenant, "sb-obs-1")).code());
        assertEquals("SANDBOX_NOT_FOUND", ((OperatorResult.Failure<?>) operatorService.health(foreignTenant, "sb-obs-1")).code());
        assertEquals("SANDBOX_NOT_FOUND", ((OperatorResult.Failure<?>) operatorService.metrics(foreignTenant, "sb-obs-1")).code());
        assertEquals("SANDBOX_NOT_FOUND", ((OperatorResult.Failure<?>) operatorService.diagnostics(foreignTenant, "sb-obs-1")).code());
        assertEquals("SANDBOX_NOT_FOUND", ((OperatorResult.Failure<?>) operatorService.stop(foreignTenant, "sb-obs-1")).code());
        assertEquals("SANDBOX_NOT_FOUND", ((OperatorResult.Failure<?>) operatorService.destroy(foreignTenant, "sb-obs-1")).code());

        Sandbox sandbox = sandboxManager.find("sb-obs-1").orElseThrow();
        assertEquals(SandboxState.RUNNING, sandbox.state());
    }

}
