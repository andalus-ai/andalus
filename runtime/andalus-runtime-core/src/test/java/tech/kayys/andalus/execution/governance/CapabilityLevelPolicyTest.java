package tech.kayys.andalus.execution.governance;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.tool.ToolInvocation;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityLevelPolicyTest {

    private final CapabilityLevelPolicy policy = new CapabilityLevelPolicy();

    private ToolPermissionContext ctx(ToolCapabilityLevel level, List<String> roles) {
        return new ToolPermissionContext("t1", "u1", "exec-1", roles, "test.tool", level);
    }

    private ToolInvocation invocation(String name) {
        return new ToolInvocation() {
            public String name() { return name; }
            public Map<String, Object> arguments() { return Map.of(); }
            public tech.kayys.andalus.identity.ResourceId id() { return new tech.kayys.andalus.identity.ResourceId.ToolId(tech.kayys.andalus.extension.Id.random()); }
            public tech.kayys.andalus.extension.Metadata metadata() { return tech.kayys.andalus.extension.Metadata.empty(); }
            public tech.kayys.andalus.resource.ResourceType type() { return tech.kayys.andalus.resource.ResourceType.fromString("tool"); }
        };
    }

    @Test
    void read_level_always_allowed() {
        PolicyDecision d = policy.evaluate(invocation("web.search"), ctx(ToolCapabilityLevel.READ, List.of()));
        assertTrue(d.isAllowed());
    }

    @Test
    void shell_denied_without_role() {
        PolicyDecision d = policy.evaluate(invocation("shell.run"), ctx(ToolCapabilityLevel.SHELL, List.of("readonly")));
        assertInstanceOf(PolicyDecision.Deny.class, d);
    }

    @Test
    void shell_allowed_with_wildcard_role() {
        PolicyDecision d = policy.evaluate(invocation("shell.run"), ctx(ToolCapabilityLevel.SHELL, List.of("*")));
        assertTrue(d.isAllowed());
    }

    @Test
    void shell_allowed_with_specific_role() {
        PolicyDecision d = policy.evaluate(invocation("shell.run"), ctx(ToolCapabilityLevel.SHELL, List.of("shell-approved")));
        assertTrue(d.isAllowed());
    }

    @Test
    void system_denied_without_role() {
        PolicyDecision d = policy.evaluate(invocation("system.restart"), ctx(ToolCapabilityLevel.SYSTEM, List.of()));
        assertInstanceOf(PolicyDecision.Deny.class, d);
    }

    @Test
    void network_denied_without_role() {
        PolicyDecision d = policy.evaluate(invocation("innocent.lookup"), ctx(ToolCapabilityLevel.NETWORK, List.of()));
        assertInstanceOf(PolicyDecision.Deny.class, d);
    }

    @Test
    void network_allowed_with_specific_role() {
        PolicyDecision d = policy.evaluate(invocation("innocent.lookup"), ctx(ToolCapabilityLevel.NETWORK, List.of("network-approved")));
        assertTrue(d.isAllowed());
    }

    @Test
    void filesystem_denied_without_role() {
        PolicyDecision d = policy.evaluate(invocation("read_file"), ctx(ToolCapabilityLevel.FILESYSTEM, List.of()));
        assertInstanceOf(PolicyDecision.Deny.class, d);
    }
}
