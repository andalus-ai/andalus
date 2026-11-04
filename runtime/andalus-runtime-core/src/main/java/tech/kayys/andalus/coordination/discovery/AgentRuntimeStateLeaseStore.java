package tech.kayys.andalus.coordination.discovery;

import java.time.Duration;

/** Mutable lease operations required by lifecycle event projection. */
public interface AgentRuntimeStateLeaseStore extends AgentRuntimeStateProvider {
    boolean publish(AgentRuntimeStateLease lease);
    boolean publish(AgentRuntimeStateLease lease, Duration ttl);
    boolean revoke(String tenantId, String agentId, String ownerId, long generation);
    boolean revokeAtOrBeforeGeneration(String tenantId, String agentId, String ownerId, long generation);
}
