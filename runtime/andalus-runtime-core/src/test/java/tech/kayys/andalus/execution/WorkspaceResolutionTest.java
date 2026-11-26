package tech.kayys.andalus.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceResolutionTest {

    @TempDir
    Path temp;

    @Test
    void canonicalizesResolverOutputBeforeExposingWorkspace() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path alias = temp.resolve("workspace-alias");
        try {
            Files.createSymbolicLink(alias, workspace);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable");
        }

        WorkspaceRequest request = new WorkspaceRequest(
                AgentCallerContext.PrincipalType.USER, "tenant-a", "user-a", "user-a", "agent-a");
        Optional<Path> resolved = WorkspaceResolution.resolve(ignored -> Optional.of(alias), request);

        assertEquals(workspace.toRealPath(), resolved.orElseThrow());
    }

    @Test
    void absentResolverOrEmptyMappingMeansNoWorkspace() {
        WorkspaceRequest request = new WorkspaceRequest(
                AgentCallerContext.PrincipalType.ANONYMOUS, null, null, null, "agent-a");

        assertTrue(WorkspaceResolution.resolve(null, request).isEmpty());
        assertTrue(WorkspaceResolution.resolve(ignored -> Optional.empty(), request).isEmpty());
    }

    @Test
    void rejectsResolverOutputThatIsNotDirectory() throws Exception {
        Path file = Files.writeString(temp.resolve("not-a-directory.txt"), "x");
        WorkspaceRequest request = new WorkspaceRequest(
                AgentCallerContext.PrincipalType.USER, "tenant-a", "user-a", "user-a", "agent-a");

        assertThrows(SecurityException.class,
                () -> WorkspaceResolution.resolve(ignored -> Optional.of(file), request));
    }

    @Test
    void workspaceRequestContainsStableIdentityButNoAuthorizationGrants() {
        WorkspaceRequest request = new WorkspaceRequest(
                AgentCallerContext.PrincipalType.USER, "tenant-a", "user-a", "user-a", "agent-a");

        assertEquals("tenant-a", request.tenantId());
        assertEquals("user-a", request.principalId());
        assertEquals("agent-a", request.agentId());
    }
}
