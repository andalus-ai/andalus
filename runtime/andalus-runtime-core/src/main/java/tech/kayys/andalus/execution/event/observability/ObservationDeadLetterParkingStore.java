package tech.kayys.andalus.execution.event.observability;

import java.util.List;
import java.util.Optional;

/**
 * Storage SPI for observation dead-letters intentionally parked by a consumer.
 *
 * <p>Implementations define their own durability and transaction guarantees.
 * The Community in-memory implementation is bounded but not durable. Removing
 * an entry returns it to the caller; reactivation in a delivery queue is a
 * separate operation owned by the application.</p>
 */
public interface ObservationDeadLetterParkingStore {
    /** Attempts to retain an entry without evicting an already parked entry. */
    ParkingResult park(ObservationDeadLetter deadLetter, String reason);

    /** Looks up a parked entry without changing its state. */
    Optional<ParkedObservationDeadLetter> find(String parkingId);

    /** Returns up to {@code limit} oldest entries without removing them. */
    List<ParkedObservationDeadLetter> list(int limit);

    /**
     * Removes and returns one parked entry so the caller can inspect, export,
     * discard, or explicitly reactivate it.
     */
    Optional<ParkedObservationDeadLetter> remove(String parkingId);

    int size();

    int capacity();

    ParkingMetrics metrics();

    enum ParkStatus {
        STORED,
        CAPACITY_REACHED
    }

    record ParkingResult(ParkStatus status, ParkedObservationDeadLetter entry) {
        public ParkingResult {
            if (status == null) throw new NullPointerException("status must not be null");
            if (status == ParkStatus.STORED && entry == null) {
                throw new IllegalArgumentException("stored result requires an entry");
            }
            if (status == ParkStatus.CAPACITY_REACHED && entry != null) {
                throw new IllegalArgumentException("capacity-rejected result must not contain an entry");
            }
        }

        public boolean stored() {
            return status == ParkStatus.STORED;
        }
    }

    record ParkingMetrics(long parked, long rejected, long removed, int size, int capacity) {
        public ParkingMetrics {
            if (parked < 0 || rejected < 0 || removed < 0 || size < 0 || capacity < 1 || size > capacity) {
                throw new IllegalArgumentException("invalid parking metrics");
            }
        }

        public double utilization() {
            return (double) size / capacity;
        }
    }
}
