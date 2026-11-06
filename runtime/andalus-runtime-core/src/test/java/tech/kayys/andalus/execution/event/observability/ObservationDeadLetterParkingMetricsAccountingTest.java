package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObservationDeadLetterParkingMetricsAccountingTest {
    private ObservationDeadLetterParkingStore.ParkingMetrics storeMetrics() {
        return new InMemoryObservationDeadLetterParkingStore(2).metrics();
    }

    @Test
    void rejectsSnapshotsWithUnaccountedParkingRejections() {
        assertThrows(IllegalArgumentException.class, () ->
                new ObservationDeadLetterParkingMetrics.Snapshot(
                        0, 1, 0, 0, 0, 0, 0, 0, storeMetrics()));
    }

    @Test
    void acceptsSnapshotWhenEveryRejectedParkHasAnExplicitOutcome() {
        assertDoesNotThrow(() ->
                new ObservationDeadLetterParkingMetrics.Snapshot(
                        2, 3, 1, 2, 0, 0, 0, 0, storeMetrics()));
    }

    @Test
    void rejectsRequeuedAndUnretainedTotalsThatDoNotMatchRejectedCount() {
        assertThrows(IllegalArgumentException.class, () ->
                new ObservationDeadLetterParkingMetrics.Snapshot(
                        0, 4, 1, 1, 0, 0, 0, 0, storeMetrics()));
    }

    @Test
    void rejectsNegativeOutcomeCounters() {
        assertThrows(IllegalArgumentException.class, () ->
                new ObservationDeadLetterParkingMetrics.Snapshot(
                        0, 0, -1, 1, 0, 0, 0, 0, storeMetrics()));
    }

    @Test
    void rejectsNegativeRecoveryCounters() {
        assertThrows(IllegalArgumentException.class, () ->
                new ObservationDeadLetterParkingMetrics.Snapshot(
                        0, 0, 0, 0, 0, 0, -1, 0, storeMetrics()));
    }

    @Test
    void rejectsMissingStoreMetrics() {
        assertThrows(NullPointerException.class, () ->
                new ObservationDeadLetterParkingMetrics.Snapshot(
                        0, 0, 0, 0, 0, 0, 0, 0, null));
    }
}
