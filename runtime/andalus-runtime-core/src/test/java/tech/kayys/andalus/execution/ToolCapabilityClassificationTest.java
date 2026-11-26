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
import tech.kayys.andalus.identity.ResourceId;
import tech.kayys.andalus.extension.Metadata;
import tech.kayys.andalus.resource.ResourceType;
import tech.kayys.andalus.execution.governance.ToolCapabilityLevel;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.Set;
import tech.kayys.andalus.execution.governance.ToolBudget;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCapabilityClassificationTest {

    @Test
    void unclassifiedDescriptorDefaultsToSystem() {
        ToolDescriptor descriptor = SimpleToolDescriptor.of("custom", "Unclassified", Map.of());
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


    @Test
    void toolContextCarriesOnlyServerCreatedExecutionAttributesAndIdentity() {
        Path workspace = Path.of("/srv/agents/workspace");
        AgentToolExecutionContext execution = new AgentToolExecutionContext(
                "exec-1", "corr-1", null, "tenant-1", "user-1",
                "agent-1", AgentToolExecutionContext.PrincipalType.USER, "user-1",
                Set.of("reader"), ExecutionBudget.balanced(), ToolBudget.unlimited(),
                Map.of("andalus.workspace.root", workspace, "andalus.execution.id", "spoofed"),
                Map.of("run.option", "trusted"), Instant.now());

        var context = DefaultAgentToolExecutor.toolContextFor(execution);
        assertEquals(workspace, context.getAttribute("andalus.workspace.root").orElseThrow());
        assertEquals("exec-1", context.getAttribute("andalus.execution.id").orElseThrow());
        assertEquals("agent-1", context.getAttribute("andalus.agent.id").orElseThrow());
        assertEquals("tenant-1", context.getAttribute("andalus.tenant.id").orElseThrow());
        assertEquals("user-1", context.getAttribute("andalus.user.id").orElseThrow());
        assertEquals(Set.of("reader"), context.getAttribute("andalus.roles").orElseThrow());
        assertEquals("trusted", context.getAttribute("run.option").orElseThrow());
    }


    @Test
    void resultCacheRequiresExplicitDeterministicProfile() {
        ToolDescriptor nonDeterministic = DefaultToolDescriptor.builder(ToolId.of("workspace"), "workspace.read")
                .executionProfile(new ToolExecutionProfile(false, true, true, Duration.ZERO, ToolKind.READ))
                .build();
        ToolDescriptor deterministic = DefaultToolDescriptor.builder(ToolId.of("pure"), "pure.lookup")
                .executionProfile(new ToolExecutionProfile(true, true, true, Duration.ZERO, ToolKind.READ))
                .build();

        assertFalse(DefaultAgentToolExecutor.isCacheable(tool(nonDeterministic)));
        assertTrue(DefaultAgentToolExecutor.isCacheable(tool(deterministic)));
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

            @Override
            public ResourceId id() {
                return descriptor.id();
            }

            @Override
            public ResourceType type() {
                return descriptor.type();
            }

            @Override
            public Metadata metadata() {
                return descriptor.metadata();
            }
        };
    }
}
