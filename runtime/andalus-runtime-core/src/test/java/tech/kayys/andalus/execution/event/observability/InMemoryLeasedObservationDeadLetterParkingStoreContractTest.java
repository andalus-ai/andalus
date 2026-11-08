package tech.kayys.andalus.execution.event.observability;

import java.time.Clock;

/** Runs the shared lease-store contract against the Community in-memory adapter. */
class InMemoryLeasedObservationDeadLetterParkingStoreContractTest
        extends LeasedObservationDeadLetterParkingStoreContract {
    @Override
    protected LeasedObservationDeadLetterParkingStore newStore(Clock clock) {
        return new InMemoryObservationDeadLetterParkingStore(16, clock);
    }
}
