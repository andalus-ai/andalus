package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ObservationDeadLetterParkingBatchResultIntegrityTest {
    @Test
    void rejectsBatchWhenRejectedEntriesAreNotFullyPartitioned() {
        var parked = parked("rejected");
        var disposition = dispositionBatch(List.of(parked));
        var rejected = List.of(new ObservationDeadLetterParkingConsumer.RejectedEntry(
                parked, ObservationDeadLetterParkingStore.ParkStatus.CAPACITY_REACHED, false));

        assertThrows(IllegalArgumentException.class, () ->
                new ObservationDeadLetterParkingConsumer.ParkingBatchResult(
                        disposition, List.of(), rejected, List.of(), List.of()));
    }

    @Test
    void rejectsBatchWhenRequeuedAndUnretainedCountsExceedRejectedCount() {
        var a = parked("a");
        var b = parked("b");
        var disposition = dispositionBatch(List.of(a, b));
        var rejected = List.of(new ObservationDeadLetterParkingConsumer.RejectedEntry(
                a, ObservationDeadLetterParkingStore.ParkStatus.CAPACITY_REACHED, true));

        assertThrows(IllegalArgumentException.class, () ->
                new ObservationDeadLetterParkingConsumer.ParkingBatchResult(
                        disposition, List.of(), rejected, List.of(a), List.of(b)));
    }

    @Test
    void rejectsNullBatchCollections() {
        assertThrows(NullPointerException.class, () ->
                new ObservationDeadLetterParkingConsumer.ParkingBatchResult(
                        dispositionBatch(List.of()), null, List.of(), List.of(), List.of()));
    }

    @Test
    void acceptsConsistentRejectedBatchAndReportsCounts() {
        var a = parked("a");
        var b = parked("b");
        var disposition = dispositionBatch(List.of(a, b));
        var rejected = List.of(
                new ObservationDeadLetterParkingConsumer.RejectedEntry(
                        a, ObservationDeadLetterParkingStore.ParkStatus.CAPACITY_REACHED, true),
                new ObservationDeadLetterParkingConsumer.RejectedEntry(
                        b, ObservationDeadLetterParkingStore.ParkStatus.CAPACITY_REACHED, false));

        var result = new ObservationDeadLetterParkingConsumer.ParkingBatchResult(
                disposition, List.of(), rejected, List.of(a), List.of(b));

        assertEquals(2, result.rejectedCount());
        assertEquals(1, result.requeuedCount());
        assertEquals(1, result.unretainedCount());
        assertFalse(result.successful());
    }

    private static ObservationDeadLetterConsumer.DispositionBatchResult dispositionBatch(
            List<ObservationDeadLetterConsumer.ParkedEntry> parked) {
        var letters = parked.stream().map(ObservationDeadLetterConsumer.ParkedEntry::deadLetter).toList();
        return new ObservationDeadLetterConsumer.DispositionBatchResult(
                letters.size(), List.of(), List.of(), List.of(), parked, List.of());
    }

    private static ObservationDeadLetterConsumer.ParkedEntry parked(String id) {
        var event = new ObservationEvent(id, "execution-1", "trace-1", "TEST",
                Instant.parse("2026-11-06T00:00:00Z"), Map.of());
        var deadLetter = new ObservationDeadLetter(event, 1, ObservationDeliveryFailure.UNKNOWN,
                IllegalStateException.class.getName(), Instant.parse("2026-11-06T00:00:01Z"));
        return new ObservationDeadLetterConsumer.ParkedEntry(deadLetter, "triage");
    }
}
