# Andalus Built-in Tools

This module contains first-party tools that are discoverable through the current
governed `tech.kayys.andalus.tool.Tool` API. It intentionally does not publish
legacy ServiceLoader tool factories.

## Workspace tools

This is an opt-in runtime module. Applications that want these beans must include
`tech.kayys.andalus:andalus-builtin-tools` on the Quarkus application classpath;
merely building the root Maven reactor does not add the tools to another application.
The module generates a Jandex index so its `@ApplicationScoped` tool beans can be
found by Quarkus CDI.

The initial workspace catalog is read-only:

| Tool | Purpose |
| --- | --- |
| `workspace.list` | List one directory |
| `workspace.read` | Read one UTF-8 text file (maximum 1 MiB) |
| `workspace.search` | Search literal text in files (bounded scan and result count) |

Each tool has an explicit `ToolExecutionProfile.readOnly(ToolKind.READ)`.
Writes, shell execution, network access, and arbitrary host filesystem access
are not part of this baseline.

### Supplying the workspace boundary

The application must set the `andalus.workspace.root` value in the trusted
`AgentDefinition.constraints()` map to a Java `Path` representing an existing
workspace directory. For example, an application-side agent definition can
include:

```java
Map<String, Object> constraints = Map.of(
    "andalus.workspace.root", Path.of("/srv/andalus/workspaces/project-123")
);
```

The agent definition and its constraints must be created or authorized by the
server/application. Never populate this value from a prompt, model-generated
tool arguments, or an untrusted request field.

The runtime copies trusted constraints and execution attributes into an
immutable `ToolContext`, then adds authoritative execution identity fields.
The workspace tools reject missing roots, non-`Path` values, absolute input
paths, traversal, and resolved symlink targets outside the canonical workspace.

Tool output paths are workspace-relative. The tools do not return arbitrary
host paths as output.

## Security limits

- Listing is limited to 200 entries.
- File reads and searchable files are limited to 1 MiB each.
- Search walks at most 12 directory levels, visits at most 20,000 entries, scans at most 5,000 regular files, and reads at most 32 MiB total per search.
- Search returns at most 200 matches with line snippets capped at 1,000 characters.
- Path inputs are capped at 4,096 characters; search queries at 1,024; glob patterns at 512.
- Workspace tools are marked non-deterministic and are excluded from the runtime result cache to prevent cross-workspace cache leakage.
- Symbolic links resolving outside the configured workspace are rejected/skipped.
- No workspace root means no filesystem operation: execution is denied.

These controls are defense in depth. Applications remain responsible for
assigning each execution the correct workspace root and for enforcing tenant
and user authorization before creating the execution.
