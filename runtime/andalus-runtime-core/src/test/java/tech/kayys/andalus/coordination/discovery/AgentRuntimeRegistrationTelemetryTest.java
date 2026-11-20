package tech.kayys.andalus.coordination.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AgentRuntimeRegistrationTelemetryTest {

    @Test
    void aggregatesLowCardinalityEventsAndTracksLastSuccessAndFailure() {
        AgentRuntimeRegistrationTelemetry telemetry = new AgentRuntimeRegistrationTelemetry();

        assertNull(telemetry.snapshot().lastSuccess());
        assertNull(telemetry.snapshot().lastFailure());

        telemetry.onEvent(AgentRuntimeRegistrationObserver.Event.ACQUIRED);
        telemetry.onEvent(AgentRuntimeRegistrationObserver.Event.RENEWED);
        telemetry.onEvent(AgentRuntimeRegistrationObserver.Event.RENEWAL_ERROR);

        var snapshot = telemetry.snapshot();
        assertEquals(1, snapshot.count(AgentRuntimeRegistrationObserver.Event.ACQUIRED));
        assertEquals(1, snapshot.count(AgentRuntimeRegistrationObserver.Event.RENEWED));
        assertEquals(1, snapshot.count(AgentRuntimeRegistrationObserver.Event.RENEWAL_ERROR));
        assertNotNull(snapshot.lastSuccess());
        assertNotNull(snapshot.lastFailure());
    }
}
