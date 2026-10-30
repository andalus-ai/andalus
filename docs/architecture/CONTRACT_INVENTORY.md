# Cross-Repository Contract Inventory — Runtime View

Status: initial source inspection; findings are candidates for design review, not an authorization to delete or rename APIs.

This document is the runtime-side companion to the framework contract inventory. The framework should own portable semantics; this repository should own concrete adapters, persistence, workers, transport and operations.

## Runtime definitions found

| Definition | Path | Current role | Recommended direction |
|---|---|---|---|
| `ExecutionCheckpointStore` | `runtime/andalus-runtime-core/src/main/java/tech/kayys/andalus/execution/ExecutionCheckpointStore.java` | Runtime repository for execution checkpoints: save/load/history/delete | Adapt to the canonical framework execution checkpoint store after contract approval |
| `ExecutionPhase` | `runtime/andalus-runtime-core/src/main/java/tech/kayys/andalus/execution/ExecutionPhase.java` | Processing phases: GUARD, INPUT, CONTEXT, PLANNING, REASONING, INFERENCE, TOOL, MEMORY, EVALUATION, OUTPUT, COMPLETE | Keep as phase/step telemetry, not lifecycle status |
| `ExecutionLifecycle` record | `runtime/andalus-runtime-core/src/main/java/tech/kayys/andalus/execution/lifecycle/ExecutionLifecycle.java` | Runtime record with executionId, status, attempt and updatedAt | Align status with framework lifecycle; keep attempt/time as runtime metadata |
| `SimpleExecutionContext` | `runtime/andalus-runtime-core/src/main/java/tech/kayys/andalus/execution/SimpleExecutionContext.java` | Adapter for linear AgentRequest path; graph/currentNode return null | Replace null graph semantics with an explicit linear invocation context or specialized graph context |

## Important dependency observation

The runtime-core POM depends on both `andalus-execution-api` and `andalus-engine`, among a broad set of other modules. The framework currently defines two interfaces with the same fully qualified name `tech.kayys.andalus.execution.ExecutionContext`:
- `andalus-execution-api/.../execution/ExecutionContext.java`
- `andalus-engine/.../execution/ExecutionContext.java`

These interfaces have different method sets. Because runtime-core brings both artifacts into its dependency graph, this is a high-priority classpath/API ambiguity to resolve. Confirm the resolved dependency tree and actual compilation/runtime class loading before describing it as a proven production failure.

## Runtime migration responsibilities

1. Inventory every import and call site for both ExecutionContext definitions.
2. Inventory implementations and consumers of all four framework CheckpointStore contracts and runtime ExecutionCheckpointStore.
3. Identify persistence adapters, serialization formats and existing checkpoint data before changing payload models.
4. Add adapter implementations that translate the canonical framework contract into current stores.
5. Preserve runtime APIs through deprecation and a planned compatibility window.
6. Add integration tests for save/load/history, duplicate delivery, stale checkpoint, resume, cancellation and partial failure.
7. Verify Maven dependency convergence and the resolved dependency tree for runtime-core.

## Runtime acceptance criteria

- Runtime core compiles against one unambiguous invocation context type.
- Graph/workflow execution uses an explicitly named specialized context.
- Runtime persistence implements the approved framework checkpoint contract through a tested adapter.
- Lifecycle status and processing phase are not conflated.
- Checkpoint serialization is versioned and migration-tested.
- Cross-repository contract tests run in CI.

## Scope limitation

This is a source-definition and POM-level inventory, not a complete usage graph. Consumer mapping, resolved dependency tree, persisted data formats and build/test outcomes remain to be established before implementation changes.
