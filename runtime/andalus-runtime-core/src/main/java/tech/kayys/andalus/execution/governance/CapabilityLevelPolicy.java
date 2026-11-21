package tech.kayys.andalus.execution.governance;

import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.andalus.tool.ToolInvocation;

import java.util.Set;

/**
 * Built-in policy: SHELL and SYSTEM capability levels are denied by default
 * unless the caller holds the "shell-approved" or "system-approved" role (§29, §30).
 *
 * <p>Priority 10 — runs first, before approval-gate policies.</p>
 */
@ApplicationScoped
public class CapabilityLevelPolicy implements ToolPolicy {

    private static final Set<ToolCapabilityLevel> HIGH_RISK =
        Set.of(ToolCapabilityLevel.EXECUTE, ToolCapabilityLevel.NETWORK,
               ToolCapabilityLevel.FILESYSTEM, ToolCapabilityLevel.SHELL,
               ToolCapabilityLevel.SYSTEM);

    @Override
    public String id() { return "capability-level-check"; }

    @Override
    public String description() { return "Denies SHELL/SYSTEM tools unless caller has explicit approval role."; }

    @Override
    public int priority() { return 10; }

    @Override
    public PolicyDecision evaluate(ToolInvocation invocation, ToolPermissionContext context) {
        ToolCapabilityLevel level = context.level();

        if (!HIGH_RISK.contains(level)) {
            return PolicyDecision.allow();
        }

        // Wildcard role "*" covers standalone/dev mode
        if (context.hasRole("*") || context.hasRole(level.name().toLowerCase() + "-approved")) {
            return PolicyDecision.allow();
        }

        return PolicyDecision.deny(
            "Capability level " + level + " is not permitted for this principal.",
            id()
        );
    }
}
