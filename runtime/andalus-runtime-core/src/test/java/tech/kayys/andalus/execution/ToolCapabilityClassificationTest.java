package tech.kayys.andalus.execution;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.tool.DefaultToolDescriptor;
import tech.kayys.andalus.tool.SimpleToolDescriptor;
import tech.kayys.andalus.tool.Tool;
import tech.kayys.andalus.tool.ToolContext;
import tech.kayys.andalus.tool.ToolDescriptor;
import tech.kayys.andalus.tool.ToolExecutionProfile;
import tech.kayys.andalus.tool.ToolId;
import tech.kayys.andalus.tool.ToolInvocation;
import tech.kayys.andalus.tool.ToolKind;
import tech.kayys.andalus.tool.ToolResult;
import tech.kayys.andalus.execution.governance.ToolCapabilityLevel;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolCapabilityClassificationTest {

    @Test
    void unclassifiedDescriptorDefaultsToSystem() {
        ToolDescriptor descriptor = SimpleToolDescriptor.of("custom", "custom", "Unclassified", Map.of());
        assertEquals(ToolCapabilityLevel.SYSTEM,
                DefaultAgentToolExecutor.resolveCapabilityLevel(tool(descriptor)));
    }

    @Test
    void explicitReadOnlyProfileMapsToRead() {
        ToolDescriptor descriptor = descriptor(ToolKind.READ, true);
        assertEquals(ToolCapabilityLevel.READ,
                DefaultAgentToolExecutor.resolveCapabilityLevel(tool(descriptor)));
    }

    @Test
    void writableReadKindCannotMasqueradeAsReadOnly() {
        ToolDescriptor descriptor = descriptor(ToolKind.READ, false);
        assertEquals(ToolCapabilityLevel.WRITE,
                DefaultAgentToolExecutor.resolveCapabilityLevel(tool(descriptor)));
    }

    @Test
    void explicitNetworkProfileMapsToNetwork() {
        ToolDescriptor descriptor = descriptor(ToolKind.NETWORK, false);
        assertEquals(ToolCapabilityLevel.NETWORK,
                DefaultAgentToolExecutor.resolveCapabilityLevel(tool(descriptor)));
    }

    @Test
    void explicitShellProfileMapsToShellRegardlessOfToolName() {
        ToolDescriptor descriptor = descriptor(ToolKind.SHELL, false);
        assertEquals(ToolCapabilityLevel.SHELL,
                DefaultAgentToolExecutor.resolveCapabilityLevel(tool(descriptor)));
    }

    private ToolDescriptor descriptor(ToolKind kind, boolean readOnly) {
        return DefaultToolDescriptor.builder(ToolId.of("test-tool"), "innocent-looking-name")
                .executionProfile(new ToolExecutionProfile(true, readOnly, true, Duration.ZERO, kind))
                .build();
    }

    private Tool tool(ToolDescriptor descriptor) {
        return new Tool() {
            @Override
            public ToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public CompletableFuture<ToolResult> execute(ToolInvocation invocation, ToolContext context) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("not executed in test"));
            }
        };
    }
}
