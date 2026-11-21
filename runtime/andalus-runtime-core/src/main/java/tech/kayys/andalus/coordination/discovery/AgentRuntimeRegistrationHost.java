package tech.kayys.andalus.coordination.discovery;

import java.time.Duration;

/**
 * Explicit embedding-host contract for one logical agent registration per process.
 *
 * <p>The application supplies stable tenant/agent identity and process-instance
 * ownership. Runtime code must not infer these values from environment variables,
 * hostnames, or the agent directory.</p>
 */
public interface AgentRuntimeRegistrationHost {

    Registration registration();

    record Registration(
            String tenantId,
            String agentId,
            String instanceId,
            String ownerId,
            Duration heartbeatInterval,
            Duration initialRetryDelay,
            Duration maxRetryDelay) {

        public Registration {
            requireText(tenantId, "tenantId");
            requireText(agentId, "agentId");
            requireText(instanceId, "instanceId");
            requireText(ownerId, "ownerId");
            requirePositive(heartbeatInterval, "heartbeatInterval");
            requirePositive(initialRetryDelay, "initialRetryDelay");
            requirePositive(maxRetryDelay, "maxRetryDelay");
            if (maxRetryDelay.compareTo(initialRetryDelay) < 0) {
                throw new IllegalArgumentException(
                        "maxRetryDelay must be greater than or equal to initialRetryDelay");
            }
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }

        private static void requirePositive(Duration value, String name) {
            if (value == null || value.isZero() || value.isNegative()
                    || value.toMillis() < 1) {
                throw new IllegalArgumentException(name + " must be at least one millisecond");
            }
        }
    }
}
