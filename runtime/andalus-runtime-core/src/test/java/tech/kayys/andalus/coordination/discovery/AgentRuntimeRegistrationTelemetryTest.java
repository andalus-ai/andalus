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
        telemetry.onRenewalDuration(10);
        telemetry.onRenewalDuration(30);

        var snapshot = telemetry.snapshot();
        assertEquals(1, snapshot.count(AgentRuntimeRegistrationObserver.Event.ACQUIRED));
        assertEquals(1, snapshot.count(AgentRuntimeRegistrationObserver.Event.RENEWED));
        assertEquals(1, snapshot.count(AgentRuntimeRegistrationObserver.Event.RENEWAL_ERROR));
        assertNotNull(snapshot.lastSuccess());
        assertNotNull(snapshot.lastFailure());
        assertEquals(2, snapshot.renewalCount());
        assertEquals(40, snapshot.totalRenewalNanos());
        assertEquals(30, snapshot.maxRenewalNanos());
        assertEquals(20, snapshot.averageRenewalNanos());
    }

    @Test
    void countsScheduledRecoveryAndManualReconciliationOutcomes() {
        AgentRuntimeRegistrationTelemetry telemetry = new AgentRuntimeRegistrationTelemetry();

        telemetry.onEvent(AgentRuntimeRegistrationObserver.Event.RECOVERY_ATTEMPTED);
        telemetry.onEvent(AgentRuntimeRegistrationObserver.Event.RECOVERY_SUCCEEDED);
        telemetry.onEvent(AgentRuntimeRegistrationObserver.Event.RECOVERY_ATTEMPTED);
        telemetry.onEvent(AgentRuntimeRegistrationObserver.Event.RECOVERY_FAILED);
        telemetry.onEvent(AgentRuntimeRegistrationObserver.Event.RECONCILIATION_ATTEMPTED);
        telemetry.onEvent(AgentRuntimeRegistrationObserver.Event.RECONCILIATION_FAILED);

        var snapshot = telemetry.snapshot();
        assertEquals(2, snapshot.count(AgentRuntimeRegistrationObserver.Event.RECOVERY_ATTEMPTED));
        assertEquals(1, snapshot.count(AgentRuntimeRegistrationObserver.Event.RECOVERY_SUCCEEDED));
        assertEquals(1, snapshot.count(AgentRuntimeRegistrationObserver.Event.RECOVERY_FAILED));
        assertEquals(1, snapshot.count(AgentRuntimeRegistrationObserver.Event.RECONCILIATION_ATTEMPTED));
        assertEquals(1, snapshot.count(AgentRuntimeRegistrationObserver.Event.RECONCILIATION_FAILED));
        assertNotNull(snapshot.lastSuccess());
        assertNotNull(snapshot.lastFailure());
    }

    @Test
    void countsRecoverySchedulerRejectionAsAnOperationalFailure() {
        AgentRuntimeRegistrationTelemetry telemetry = new AgentRuntimeRegistrationTelemetry();

        telemetry.onEvent(AgentRuntimeRegistrationObserver.Event.RECOVERY_SCHEDULING_REJECTED);

        var snapshot = telemetry.snapshot();
        assertEquals(1, snapshot.count(
                AgentRuntimeRegistrationObserver.Event.RECOVERY_SCHEDULING_REJECTED));
        assertNotNull(snapshot.lastFailure());
    }

}
