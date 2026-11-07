package tech.kayys.andalus.execution.event.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Optional parking-store capability for coordinating recovery across workers.
 *
 * <p>Implementations must make claim acquisition, renewal, completion, and
 * release atomic with respect to other operations on the same parking ID.
 * Claim tokens are fencing values: stale owners cannot mutate newer claims.
 * Durable implementations should persist claims and enforce token comparison
 * transactionally.</p>
 */
public interface LeasedObservationDeadLetterParkingStore extends ObservationDeadLetterParkingStore {
    ClaimResult claim(String parkingId, String owner, Duration leaseDuration);
    Optional<RecoveryClaim> renew(RecoveryClaim claim, Duration leaseDuration);
    Optional<ParkedObservationDeadLetter> complete(RecoveryClaim claim);
    boolean release(RecoveryClaim claim);

    enum ClaimStatus { CLAIMED, ALREADY_CLAIMED, NOT_FOUND }

    record ClaimResult(ClaimStatus status, RecoveryClaim claim) {
        public ClaimResult {
            Objects.requireNonNull(status, "status must not be null");
            if (status == ClaimStatus.CLAIMED && claim == null)
                throw new IllegalArgumentException("claimed result requires claim details");
            if (status != ClaimStatus.CLAIMED && claim != null)
                throw new IllegalArgumentException("non-claimed result must not expose a claim token");
        }
        public boolean claimed() { return status == ClaimStatus.CLAIMED; }
    }

    record RecoveryClaim(String parkingId, String token, String owner, Instant leaseUntil,
                        ParkedObservationDeadLetter entry) {
        public RecoveryClaim {
            if (parkingId == null || parkingId.isBlank()) throw new IllegalArgumentException("parkingId must not be blank");
            if (token == null || token.isBlank()) throw new IllegalArgumentException("token must not be blank");
            if (owner == null || owner.isBlank()) throw new IllegalArgumentException("owner must not be blank");
            Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
            Objects.requireNonNull(entry, "entry must not be null");
            if (!parkingId.equals(entry.parkingId())) throw new IllegalArgumentException("claim entry ID mismatch");
        }
    }
}
