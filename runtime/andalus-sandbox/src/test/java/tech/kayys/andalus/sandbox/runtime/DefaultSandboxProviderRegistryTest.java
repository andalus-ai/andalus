package tech.kayys.andalus.sandbox.runtime;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.extension.Version;
import tech.kayys.andalus.spi.sandbox.*;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSandboxProviderRegistryTest {

    @Test
    void doesNotDowngradeContainerRequestToProcessProvider() {
        DefaultSandboxProviderRegistry registry = new DefaultSandboxProviderRegistry();
        registry.register(processProvider());

        SandboxRequest request = new SandboxRequest(
                "container-required",
                SandboxType.CONTAINER,
                Set.of(),
                SandboxLimits.unlimited(),
                SandboxFilesystem.empty(),
                SandboxNetwork.disabled(),
                Map.of(),
                Map.of());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> registry.select(request));

        assertTrue(error.getMessage().contains("CONTAINER"));
    }

    @Test
    void selectsProviderThatExplicitlySupportsRequestedType() {
        DefaultSandboxProviderRegistry registry = new DefaultSandboxProviderRegistry();
        SandboxProvider process = processProvider();
        registry.register(process);

        SandboxRequest request = new SandboxRequest(
                "process-request",
                SandboxType.PROCESS,
                Set.of(),
                SandboxLimits.unlimited(),
                SandboxFilesystem.empty(),
                SandboxNetwork.disabled(),
                Map.of(),
                Map.of());

        assertSame(process, registry.select(request));
    }

    private SandboxProvider processProvider() {
        return new SandboxProvider() {
            @Override
            public String providerId() {
                return "test.process";
            }

            @Override
            public SandboxProviderDescriptor descriptor() {
                return new SandboxProviderDescriptor(
                        providerId(),
                        "Test Process Provider",
                        "Test-only process provider",
                        Version.parse("1.0.0"),
                        Set.of(SandboxType.PROCESS),
                        Set.of(IsolationFeature.PROCESS_ISOLATION),
                        Set.of(),
                        Set.of(),
                        Map.of());
            }
        };
    }
}
