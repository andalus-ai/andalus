package tech.kayys.andalus.execution.event.observability;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ObservationDeliveryFailureTest {
    @Test void classifiesKnownTransientAndPermanentFailures() {
        assertEquals(ObservationDeliveryFailure.TRANSIENT,
                ObservationDeliveryFailure.classify(new java.net.SocketTimeoutException()));
        assertEquals(ObservationDeliveryFailure.TRANSIENT,
                ObservationDeliveryFailure.classify(new java.io.IOException()));
        assertEquals(ObservationDeliveryFailure.PERMANENT,
                ObservationDeliveryFailure.classify(new IllegalArgumentException()));
        assertEquals(ObservationDeliveryFailure.PERMANENT,
                ObservationDeliveryFailure.classify(new SecurityException()));
        assertEquals(ObservationDeliveryFailure.UNKNOWN,
                ObservationDeliveryFailure.classify(new IllegalIllegalStateException()));
    }
    private static RuntimeException newIllegalStateException() { return new IllegalStateException(); }
}
