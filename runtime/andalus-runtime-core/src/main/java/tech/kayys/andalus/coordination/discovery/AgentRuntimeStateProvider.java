package tech.kayys.andalus.coordination.discovery;

import java.util.Optional;

import tech.kayys.andalus.coordination.CoordinationContext;
import tech.kayys.andalus.coordination.CoordinationRequest;

/**
 * Supplies live operational state for an agent profile.
 *
 * <p>Implementations should return empty when the agent is not managed by
 * that provider or is not visible in the supplied execution context. This
 * SPI does not authorize invocation or reserve capacity.</p>
 */
public interface AgentRuntimeStateProvider {

    Optional<AgentRuntimeState> resolve(
            String agentId,
            CoordinationRequest request,
            CoordinationContext context);
}
