package tech.kayys.andalus.tools.workspace;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tech.kayys.andalus.tool.ToolArguments;
import tech.kayys.andalus.tool.ToolId;
import tech.kayys.andalus.tool.ToolInvocation;

class WorkspaceToolsTest {

    @TempDir
    Path temp;

    private ToolInvocation invocation(String name, Map<String, Object> arguments) {
        return ToolInvocation.of(ToolId.of(name), ToolArguments.of(arguments));
    }

    private tech.kayys.andalus.tool.SimpleToolContext context(Path workspace) {
        return tech.kayys.andalus.tool.SimpleToolContext.of(
                Map.of(WorkspaceToolSupport.WORKSPACE_ROOT, workspace));
    }

    @Test
    void readsTextFileAndReturnsWorkspaceRelativePath() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Files.writeString(workspace.resolve("notes.txt"), "hello from workspace");

        var result = new WorkspaceReadTool().execute(
                invocation("workspace.read", Map.of("path", "notes.txt")), context(workspace)).join();

        assertTrue(result.isSuccess());
        assertEquals("hello from workspace", result.getOutputs().get("content"));
        assertEquals("notes.txt", result.getOutputs().get("path"));
    }

    @Test
    void rejectsTraversalAtToolBoundary() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Files.writeString(temp.resolve("outside.txt"), "not accessible");

        var result = new WorkspaceReadTool().execute(
                invocation("workspace.read", Map.of("path", "../outside.txt")), context(workspace)).join();

        assertFalse(result.isSuccess());
        assertEquals(tech.kayys.andalus.tool.ToolResultStatus.DENIED, result.status());
    }

    @Test
    void listsAndSearchesOnlyWorkspaceFiles() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/Main.java"), "class Main { String marker = \"needle\"; }");

        var listing = new WorkspaceListTool().execute(
                invocation("workspace.list", Map.of("path", "src")), context(workspace)).join();
        assertTrue(listing.isSuccess());
        assertEquals("Main.java", ((Map<?, ?>) ((java.util.List<?>) listing.getOutputs().get("entries")).getFirst()).get("name"));

        var search = new WorkspaceSearchTool().execute(
                invocation("workspace.search", Map.of("query", "needle", "glob", "**/*.java")), context(workspace)).join();
        assertTrue(search.isSuccess());
        assertEquals(1, ((java.util.List<?>) search.getOutputs().get("matches")).size());
    }

    @Test
    void refusesToRunWithoutServerSuppliedWorkspace() {
        var result = new WorkspaceListTool().execute(
                invocation("workspace.list", Map.of()), tech.kayys.andalus.tool.SimpleToolContext.empty()).join();
        assertEquals(tech.kayys.andalus.tool.ToolResultStatus.DENIED, result.status());
    }
}
