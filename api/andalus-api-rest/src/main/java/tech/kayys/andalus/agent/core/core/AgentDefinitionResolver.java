package tech.kayys.andalus.agent.core.core;

import java.util.Optional;
import tech.kayys.andalus.agent.spi.AgentRequest;
import tech.kayys.andalus.core.AgentDefinition;
import tech.kayys.andalus.execution.AgentCallerContext;

/**
 * Resolves an executable agent definition for a trusted caller.
 *
 * <p>Implementations are responsible for tenant-scoped lookup and
 * agent-level invocation authorization. They also receive the requested
 * execution options and must reject options the resolved definition/runtime
 * cannot honor. A runtime registration or public agent card is discovery
 * metadata, not an authorization grant.</p>
 */
@FunctionalInterface
public interface AgentDefinitionResolver {

    /**
     * Resolves the requested agent only when it belongs to the caller's
     * tenant, the caller is authorized to invoke it, and the requested
     * execution options are compatible with its definition.
     */
    Optional<AgentDefinition> resolve(
            String agentId, AgentRequest request, AgentCallerContext caller);
}
