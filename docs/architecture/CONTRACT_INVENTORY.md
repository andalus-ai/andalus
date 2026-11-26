# Cross-Repository Contract Inventory — Runtime View

Status: refreshed targeted source inventory as of 2026-11-25. Findings are evidence for design review, not authorization to delete or rename APIs.

This document is the runtime-side companion to the framework contract inventory. The framework should own portable semantics; this repository should own concrete adapters, persistence, workers, transport and operations.

## Runtime definitions found

| Definition | Path | Current role | Recommended direction |
|---|---|---|---|
| `ExecutionCheckpointStore` | `runtime/andalus-runtime-core/src/main/java/tech/kayys/andalus/execution/ExecutionCheckpointStore.java` | Runtime repository for execution checkpoints: save/load/history/delete | Adapt to the canonical framework execution checkpoint store after contract approval |
| `ExecutionPhase` | `runtime/andalus-runtime-core/src/main/java/tech/kayys/andalus/execution/ExecutionPhase.java` | Processing phases: GUARD, INPUT, CONTEXT, PLANNING, REASONING, INFERENCE, TOOL, MEMORY, EVALUATION, OUTPUT, COMPLETE | Keep as phase/step telemetry, not lifecycle status |
| `ExecutionLifecycle` record | `runtime/andalus-runtime-core/src/main/java/tech/kayys/andalus/execution/lifecycle/ExecutionLifecycle.java` | Runtime record with executionId, status, attempt and updatedAt | Align status with framework lifecycle; keep attempt/time as runtime metadata |
| `SimpleGraphExecutionContext` | `runtime/andalus-runtime-core/src/main/java/tech/kayys/andalus/execution/SimpleGraphExecutionContext.java` | Linear AgentRequest adapter implementing Framework `GraphExecutionContext`; graph/currentNode return null | Keep graph-only operations out of the linear path; replace null semantics with a typed linear context or explicit optional graph capability after consumer review |

## Important dependency observation

The runtime-core POM depends on both `andalus-execution-api` and `andalus-engine`. Current Framework source uses distinct FQNs: invocation `ExecutionContext` in `andalus-execution-api` and graph `GraphExecutionContext` in `andalus-engine`. The former duplicate-FQN concern is therefore resolved in source. A resolved dependency tree and clean compile are still required to verify the complete artifact graph and compatibility. The remaining runtime issue is semantic propagation: the linear `SimpleGraphExecutionContext` adapter returns null for graph/current-node concepts that are not applicable to its path.

## Runtime migration responsibilities

1. Complete the import/call-site and binary-compatibility scan for invocation `ExecutionContext` and graph `GraphExecutionContext`; do not repeat the completed graph rename.
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

## Current source snapshot — 2026-11-25

- The duplicate Framework `ExecutionContext` FQN is no longer present in current source: the graph contract is `GraphExecutionContext`.
- Framework `DefaultExecutionContext` now validates invocation lifecycle transitions atomically.
- Framework `InMemoryExecutionCheckpointStore` enforces per-execution monotonic sequence and duplicate checkpoint IDs under synchronization; it remains process-local.
- Runtime `InMemoryExecutionCheckpointStore` currently appends lifecycle checkpoints without equivalent duplicate-ID or monotonic-sequence enforcement.
- Runtime `DefaultAgentExecution` uses both the Runtime `ExecutionCheckpointStore` and the Framework Runtime-SPI AgentContext `CheckpointStore`. The resume path remains tied to the AgentContext store, so the newer lifecycle checkpoint is not yet the single authoritative recovery source.
- Runtime pause/resume remain status/checkpoint operations rather than proven safe-point suspension and continuation. Cancellation now has an execution token passed to the ReAct/tool bridge and completes the caller future on cancellation; provider-level interruption and no-late-side-effect guarantees still need tests.

## Scope limitation

This is a source-definition and POM-level inventory, not a complete usage graph. Consumer mapping, resolved dependency tree, persisted data formats and build/test outcomes remain to be established before implementation changes.
