package tech.kayys.andalus.coordination.discovery;

import java.util.List;

import tech.kayys.andalus.coordination.CoordinationContext;
import tech.kayys.andalus.coordination.CoordinationRequest;

/**
 * SPI implemented by runtime modules that can advertise delegation candidates.
 *
 * <p>Implementations should report only agents visible in the current runtime
 * scope. Returned capabilities are descriptive routing metadata, never grants
 * of permission. Implementations must not execute or reserve work here.</p>
 */
public interface AgentCapabilitySource {

    List<AgentCapabilityCandidate> discover(
            CoordinationRequest request,
            CoordinationContext context);
}
