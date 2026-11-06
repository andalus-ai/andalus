package tech.kayys.andalus.execution.event.observability;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded, process-local parking store for Community deployments.
 *
 * <p>Entries are retained in insertion order. At capacity, new entries are
 * rejected rather than evicting previously parked work. All operations are
 * synchronized to provide a consistent in-process view; this class does not
 * provide persistence, cross-process coordination, or crash recovery.</p>
 */
public final class InMemoryObservationDeadLetterParkingStore
        implements LeasedObservationDeadLetterParkingStore {

    private final int capacity;
    private final Clock clock;
    private final LinkedHashMap<String, ParkedObservationDeadLetter> entries = new LinkedHashMap<>();
    private long parkedCount;
    private long rejectedCount;
    private long removedCount;
    private final Map<String, RecoveryClaim> claims = new HashMap<>();

    public InMemoryObservationDeadLetterParkingStore(int capacity) {
        this(capacity, Clock.systemUTC());
    }

    public InMemoryObservationDeadLetterParkingStore(int capacity, Clock clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be greater than zero");
        }
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public synchronized ParkingResult park(ObservationDeadLetter deadLetter, String reason) {
        Objects.requireNonNull(deadLetter, "deadLetter must not be null");
        if (entries.size() >= capacity) {
            rejectedCount++;
            return new ParkingResult(ParkStatus.CAPACITY_REACHED, null);
        }
        String id = UUID.randomUUID().toString();
        ParkedObservationDeadLetter parked = new ParkedObservationDeadLetter(
                id, deadLetter, reason, Instant.now(clock));
        entries.put(id, parked);
        parkedCount++;
        return new ParkingResult(ParkStatus.STORED, parked);
    }

    @Override
    public synchronized Optional<ParkedObservationDeadLetter> find(String parkingId) {
        return Optional.ofNullable(entries.get(requireId(parkingId)));
    }

    @Override
    public synchronized List<ParkedObservationDeadLetter> list(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
        List<ParkedObservationDeadLetter> result = new ArrayList<>(Math.min(limit, entries.size()));
        for (ParkedObservationDeadLetter entry : entries.values()) {
            if (result.size() == limit) break;
            result.add(entry);
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized Optional<ParkedObservationDeadLetter> remove(String parkingId) {
        ParkedObservationDeadLetter removed = entries.remove(requireId(parkingId));
        if (removed != null) removedCount++;
        return Optional.ofNullable(removed);
    }

    @Override
    public synchronized ClaimResult claim(String parkingId, String owner, java.time.Duration leaseDuration) {
        String id = requireId(parkingId);
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("owner must not be blank");
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        if (leaseDuration.isZero() || leaseDuration.isNegative())
            throw new IllegalArgumentException("leaseDuration must be positive");
        ParkedObservationDeadLetter entry = entries.get(id);
        if (entry == null) return new ClaimResult(ClaimStatus.NOT_FOUND, null);
        Instant now = Instant.now(clock);
        RecoveryClaim existing = claims.get(id);
        if (existing != null && existing.leaseUntil().isAfter(now)) {
            return new ClaimResult(ClaimStatus.ALREADY_CLAIMED, existing);
        }
        RecoveryClaim claim = new RecoveryClaim(id, UUID.randomUUID().toString(), owner, now.plus(leaseDuration), entry);
        claims.put(id, claim);
        return new ClaimResult(ClaimStatus.CLAIMED, claim);
    }

    @Override
    public synchronized Optional<ParkedObservationDeadLetter> complete(RecoveryClaim claim) {
        Objects.requireNonNull(claim, "claim must not be null");
        RecoveryClaim current = claims.get(requireId(claim.parkingId()));
        if (current == null || !current.token().equals(claim.token())) return Optional.empty();
        ParkedObservationDeadLetter removed = entries.remove(claim.parkingId());
        claims.remove(claim.parkingId());
        if (removed != null) removedCount++;
        return Optional.ofNullable(removed);
    }

    @Override
    public synchronized boolean release(RecoveryClaim claim) {
        Objects.requireNonNull(claim, "claim must not be null");
        RecoveryClaim current = claims.get(requireId(claim.parkingId()));
        if (current == null || !current.token().equals(claim.token())) return false;
        claims.remove(claim.parkingId());
        return true;
    }

    @Override
    public synchronized int size() {
        return entries.size();
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public synchronized ParkingMetrics metrics() {
        return new ParkingMetrics(parkedCount, rejectedCount, removedCount, entries.size(), capacity);
    }

    private static String requireId(String parkingId) {
        if (parkingId == null || parkingId.isBlank()) {
            throw new IllegalArgumentException("parkingId must not be blank");
        }
        return parkingId;
    }
}
