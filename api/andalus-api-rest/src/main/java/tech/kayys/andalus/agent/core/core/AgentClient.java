package tech.kayys.andalus.agent.core.core;

import java.util.Objects;

import io.smallrye.mutiny.Uni;
import tech.kayys.andalus.agent.spi.AgentRequest;
import tech.kayys.andalus.agent.spi.AgentResponse;
import tech.kayys.andalus.execution.AgentCallerContext;

/**
 * Transport-facing agent execution contract.
 *
 * <p>Authenticated callers must be passed through an implementation that
 * understands {@link AgentCallerContext}. The compatibility default is
 * deliberately limited to completely anonymous, unscoped requests; it never
 * drops tenant or authorization claims.</p>
 */
public interface AgentClient {
    Uni<AgentResponse> execute(AgentRequest request);

    /**
     * Executes with the immutable caller snapshot established by the trusted
     * transport boundary.
     *
     * <p>Implementations that can propagate caller context should override
     * this method and pass it to the execution service. Legacy implementations
     * remain usable only for unscoped anonymous requests.</p>
     */
    default Uni<AgentResponse> execute(AgentRequest request, AgentCallerContext caller) {
        Objects.requireNonNull(caller, "caller");
        if (caller.principalType() == AgentCallerContext.PrincipalType.ANONYMOUS
                && caller.tenantId() == null
                && caller.roles().isEmpty()
                && caller.permissions().isEmpty()) {
            return execute(request);
        }
        return Uni.createFrom().failure(new UnsupportedOperationException(
                "AgentClient implementation does not support trusted caller context; refusing to drop identity"));
    }
}
