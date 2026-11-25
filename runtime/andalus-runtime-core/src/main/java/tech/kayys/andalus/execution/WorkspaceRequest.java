package tech.kayys.andalus.execution;

/**
 * Minimal authenticated identity used to resolve an execution's workspace.
 *
 * <p>Roles, permissions, prompt content, and arbitrary caller attributes are
 * intentionally excluded. A workspace resolver should make its decision from
 * stable identity and the selected agent only.</p>
 */
public record WorkspaceRequest(
        AgentCallerContext.PrincipalType principalType,
        String tenantId,
        String principalId,
        String userId,
        String agentId) {

    public WorkspaceRequest {
        principalType = java.util.Objects.requireNonNull(principalType, "principalType");
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        agentId = agentId.trim();
        if (principalType != AgentCallerContext.PrincipalType.ANONYMOUS
                && (principalId == null || principalId.isBlank())) {
            throw new IllegalArgumentException("authenticated workspace requests require principalId");
        }
    }
}
