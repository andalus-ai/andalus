package tech.kayys.andalus.tools.workspace;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tech.kayys.andalus.tool.SimpleToolContext;
import tech.kayys.andalus.tool.ToolKind;

class WorkspaceToolSecurityTest {

    @TempDir
    Path temp;

    @Test
    void resolvesFilesOnlyInsideConfiguredWorkspace() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path file = Files.writeString(workspace.resolve("readme.txt"), "hello");
        var context = SimpleToolContext.of(Map.of(WorkspaceToolSupport.WORKSPACE_ROOT, workspace));

        assertEquals(file.toRealPath(), WorkspaceToolSupport.resolveExisting(context, "readme.txt"));
        assertThrows(SecurityException.class,
                () -> WorkspaceToolSupport.resolveExisting(context, "../outside.txt"));
        assertThrows(SecurityException.class,
                () -> WorkspaceToolSupport.resolveExisting(context, file.toAbsolutePath().toString()));
    }

    @Test
    void rejectsMissingOrUntrustedWorkspaceRoot() {
        assertThrows(SecurityException.class,
                () -> WorkspaceToolSupport.workspaceRoot(SimpleToolContext.empty()));
        assertThrows(SecurityException.class,
                () -> WorkspaceToolSupport.workspaceRoot(
                        SimpleToolContext.of(Map.of(WorkspaceToolSupport.WORKSPACE_ROOT, temp.toString()))));
    }

    @Test
    void rejectsSymlinkThatResolvesOutsideWorkspace() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Path link = workspace.resolve("outside-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are not available in this environment");
        }
        var context = SimpleToolContext.of(Map.of(WorkspaceToolSupport.WORKSPACE_ROOT, workspace));
        assertThrows(SecurityException.class,
                () -> WorkspaceToolSupport.resolveExisting(context, "outside-link/secret.txt"));
    }

    @Test
    void builtInToolsDeclareReadOnlyRiskProfiles() {
        assertReadOnly(new WorkspaceListTool());
        assertReadOnly(new WorkspaceReadTool());
        assertReadOnly(new WorkspaceSearchTool());
    }

    private void assertReadOnly(WorkspaceToolSupport tool) {
        var profile = tool.descriptor().executionProfile();
        assertEquals(ToolKind.READ, profile.kind());
        assertTrue(profile.readOnly());
        assertFalse(profile.deterministic(), "workspace content depends on external filesystem state and must not be cached");
    }
}
