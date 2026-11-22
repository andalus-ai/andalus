package tech.kayys.andalus.execution;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionResumeIdentityTest {

    private static Map<String, Object> userCheckpoint() {
        return Map.of(
                "tenantId", "tenant-a",
                "userId", "user-1",
                "principalType", "USER",
                "principalId", "user-1",
                "agentId", "agent-1");
    }

    @Test
    void permitsResumeByTheExactCheckpointOwner() {
        assertDoesNotThrow(() ->
                AgentExecutionService.verifyResumeIdentity(userCheckpoint(), "tenant-a", "user-1"));
    }

    @Test
    void rejectsResumeFromAnotherTenant() {
        assertThrows(SecurityException.class, () ->
                AgentExecutionService.verifyResumeIdentity(userCheckpoint(), "tenant-b", "user-1"));
    }

    @Test
    void rejectsResumeFromAnotherUserInTheSameTenant() {
        assertThrows(SecurityException.class, () ->
                AgentExecutionService.verifyResumeIdentity(userCheckpoint(), "tenant-a", "user-2"));
    }

    @Test
    void rejectsAnonymousResumeOfAUserOwnedExecution() {
        assertThrows(SecurityException.class, () ->
                AgentExecutionService.verifyResumeIdentity(userCheckpoint(), "tenant-a", null));
    }

    @Test
    void rejectsAuthenticatedResumeOfLegacyCheckpointWithoutIdentityMetadata() {
        assertThrows(SecurityException.class, () ->
                AgentExecutionService.verifyResumeIdentity(Map.of(), "tenant-a", "user-1"));
    }

    @Test
    void preservesAnonymousCompatibilityForLegacyCheckpointWithoutMetadata() {
        assertDoesNotThrow(() ->
                AgentExecutionService.verifyResumeIdentity(Map.of(), null, null));
    }

    @Test
    void rejectsPrincipalIdMismatchEvenWhenTenantAndUserFieldsMatch() {
        Map<String, Object> mismatchedPrincipal = Map.of(
                "tenantId", "tenant-a",
                "userId", "user-1",
                "principalType", "USER",
                "principalId", "different-principal");

        assertThrows(SecurityException.class, () ->
                AgentExecutionService.verifyResumeIdentity(mismatchedPrincipal, "tenant-a", "user-1"));
    }
}
