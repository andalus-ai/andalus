package tech.kayys.andalus.runtime.identity;

/** Durable PostgreSQL lease token carried to each rotation persistence boundary. */
public record AnpKeyRotationFence(String operationId, String workerId, long generation,
                                  String expectedState) {
    public AnpKeyRotationFence {
        if (operationId == null || operationId.isBlank()) throw new IllegalArgumentException("operationId must not be blank");
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("workerId must not be blank");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        if (expectedState == null || expectedState.isBlank()) throw new IllegalArgumentException("expectedState must not be blank");
    }
}
