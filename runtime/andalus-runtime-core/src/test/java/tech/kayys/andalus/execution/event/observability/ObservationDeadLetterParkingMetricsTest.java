package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.observability.ObservationEvent;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ObservationDeadLetterParkingMetricsTest {
    @Test
    void reportsParkingRejectionAndRecoveryOutcomes() {
        var metrics = new ObservationDeadLetterParkingMetrics();
        var store = new InMemoryObservationDeadLetterParkingStore(1);
        var queue = new BoundedObservationDeadLetterQueue(2,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        store.park(letter("existing"), "hold");
        queue.offer(letter("rejected"));
        var consumer = ObservationDeadLetterConsumer.withDispositionHandler(queue,
                ignored -> ObservationDeadLetterDisposition.park("review"));
        var parkingConsumer = new ObservationDeadLetterParkingConsumer(consumer, store, metrics);

        var batch = parkingConsumer.consume(1);
        assertEquals(1, batch.rejectedCount());
        assertEquals(1, parkingConsumer.metrics().rejected());

        var recovery = new ObservationDeadLetterParkingRecovery(store, queue, metrics);
        var id = store.list(1).get(0).parkingId();
        assertEquals(ObservationDeadLetterParkingRecovery.Status.REACTIVATED,
                recovery.recover(id).status());
        assertEquals(1, recovery.metrics().recoverySucceeded());
        assertEquals(0, recovery.metrics().recoveryQueueFull());
    }

    @Test
    void countsQueueFullAndNotFoundRecovery() {
        var metrics = new ObservationDeadLetterParkingMetrics();
        var store = new InMemoryObservationDeadLetterParkingStore(1);
        var parked = store.park(letter("parked"), "manual").entry();
        var queue = new BoundedObservationDeadLetterQueue(1,
                BoundedObservationDeadLetterQueue.OverflowPolicy.DROP_NEWEST);
        queue.offer(letter("occupy"));
        var recovery = new ObservationDeadLetterParkingRecovery(store, queue, metrics);

        assertEquals(ObservationDeadLetterParkingRecovery.Status.QUEUE_CAPACITY_REACHED,
                recovery.recover(parked.parkingId()).status());
        assertEquals(ObservationDeadLetterParkingRecovery.Status.NOT_FOUND,
                recovery.recover("missing").status());
        var snapshot = recovery.metrics();
        assertEquals(1, snapshot.recoveryQueueFull());
        assertEquals(1, snapshot.recoveryNotFound());
        assertEquals(1, snapshot.store().size());
    }

    private static ObservationDeadLetter letter(String id) {
        var event = new ObservationEvent(id, "execution-1", "trace-1", "TEST",
                Instant.parse("2026-11-05T00:00:00Z"), Map.of());
        return new ObservationDeadLetter(event, 1, ObservationDeliveryFailure.UNKNOWN,
                IllegalStateException.class.getName(), Instant.parse("2026-11-05T00:00:01Z"));
    }
}
