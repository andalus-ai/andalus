package tech.kayys.andalus.agent.core.core;

import java.util.Optional;
import tech.kayys.andalus.core.AgentDefinition;
import tech.kayys.andalus.execution.AgentCallerContext;

/**
 * Resolves an executable agent definition for a trusted caller.
 *
 * <p>Implementations are responsible for tenant-scoped lookup and agent-level
 * invocation authorization. A runtime registration or public agent card is
 * discovery metadata, not an authorization grant.</p>
 */
@FunctionalInterface
public interface AgentDefinitionResolver {

    /**
     * Resolves the requested agent only when it belongs to the caller's
     * tenant and the caller is authorized to invoke it.
     */
    Optional<AgentDefinition> resolve(String agentId, AgentCallerContext caller);
}
