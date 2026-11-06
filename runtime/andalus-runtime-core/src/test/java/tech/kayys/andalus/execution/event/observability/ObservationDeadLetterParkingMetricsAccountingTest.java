package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObservationDeadLetterParkingMetricsAccountingTest {
    @Test
    void rejectsSnapshotsWithUnaccountedParkingRejections() {
        var store = new InMemoryObservationDeadLetterParkingStore(2).metrics();
        assertThrows(IllegalArgumentException.class, () ->
                new ObservationDeadLetterParkingMetrics.Snapshot(
                        0, 1, 0, 0, 0, 0, 0, 0, store));
    }

    @Test
    void acceptsSnapshotWhenEveryRejectedParkHasAnExplicitOutcome() {
        var store = new InMemoryObservationDeadLetterParkingStore(2).metrics();
        assertDoesNotThrow(() ->
                new ObservationDeadLetterParkingMetrics.Snapshot(
                        2, 3, 1, 2, 0, 0, 0, 0, store));
    }

    @Test
    void rejectsNegativeOutcomeCounters() {
        var store = new InMemoryObservationDeadLetterParkingStore(2).metrics();
        assertThrows(IllegalArgumentException.class, () ->
                new ObservationDeadLetterParkingMetrics.Snapshot(
                        0, 0, -1, 1, 0, 0, 0, 0, store));
    }
}
