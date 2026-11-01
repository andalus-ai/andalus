# Contract Consumer and Implementation Map — Runtime

Status: first-pass map from the `docs/development-guidelines-and-agents` branch. This is a source-level map, not a complete compiled dependency graph. Entries below distinguish directly inspected call sites from inferred/transitive relationships.

## 1. ExecutionContext

| Role | Source | Observed use | Confidence |
|---|---|---|---|
| Runtime entry point | `runtime/andalus-runtime-core/.../core/DefaultAndalusRuntime.java` | Creates `SimpleExecutionContext.fromRequest(request)` and passes it to `ExecutionEngine.executeAgent` | Direct |
| Graph context implementation | `runtime/andalus-runtime-core/.../execution/SimpleExecutionContext.java` | Implements graph-style `tech.kayys.andalus.execution.ExecutionContext`; returns null for graph/currentNode on linear path | Direct |
| Engine implementation | `runtime/andalus-runtime-core/.../execution/DefaultExecutionEngine.java` | Implements `ExecutionEngine` and accepts `ExecutionContext` in agent/workflow/skill methods | Direct |
| Framework engine contract | Framework `andalus-engine/.../execution/ExecutionEngine.java` | Method signatures refer to the engine module's same-package `ExecutionContext` | Direct |
| Framework invocation context | Framework `andalus-execution-api/.../execution/ExecutionContext.java` | Separate interface with same FQN but invocation metadata/cancellation/security/lifecycle methods | Definition confirmed; runtime call-site binding not yet verified |

### Dependency/classpath concern

Runtime-core directly declares dependencies on both `andalus-execution-api` and `andalus-engine`. Both artifacts contain a class named `tech.kayys.andalus.execution.ExecutionContext`, with different method sets. Runtime source methods and framework `ExecutionEngine` bytecode therefore need an explicit classpath-resolution check. A Maven dependency tree and actual reactor compile are required to determine whether this manifests as a compile error, linkage issue or dependency-order-sensitive behavior.

### Migration implications

- Preserve the current graph execution contract while renaming it to a graph/workflow-specific FQN.
- Make runtime's top-level invocation context explicit; do not silently make a linear agent request pretend to have a graph.
- Add tests proving the runtime entry point and workflow execution each bind to the intended contract.
- Do not change the method signature until the resolved dependency graph and all implementations are mapped.

## 2. Checkpoint stores

| Contract | Runtime consumer/implementation | Observed behavior | Confidence |
|---|---|---|---|
| Framework `tech.kayys.andalus.execution.CheckpointStore` from `andalus-runtime-spi` | Runtime `InMemoryCheckpointStore`, `AgentExecutionService`, `DefaultAgentExecution` | Stores/restores `AgentContext`; save occurs in ReAct checkpoint callback, completion, stub completion and pause; resume loads latest context | Direct |
| Runtime `ExecutionCheckpointStore` | Runtime `InMemoryExecutionCheckpointStore`, `AgentExecutionService`, `DefaultAgentExecution`, `DefaultExecutionStateStore` | Stores execution state snapshots; `DefaultExecutionStateStore.checkpoint` creates and saves runtime `ExecutionCheckpoint` values | Direct |
| Framework `execution.checkpoint.CheckpointStore` | No direct runtime implementation/call site confirmed in inspected runtime files | Uses framework `ExecutionCheckpoint` (attempt/sequence/payload/manifest) | Not yet mapped |
| Framework `state.checkpoint.CheckpointStore` | No direct runtime implementation/call site confirmed in inspected runtime files | Stores state API `Checkpoint` (state/context/artifact/provenance envelope) | Not yet mapped |
| Framework `tech.kayys.andalus.execution.CheckpointStore` from `andalus-engine` | No runtime consumer/implementation confirmed | Stores engine `ExecutionSnapshot`; default `getLatest` returns null | Not yet mapped |

### Confirmed runtime checkpoint flow

1. `AgentExecutionService` injects runtime-SPI `CheckpointStore` and optional `ExecutionCheckpointStore`.
2. It passes both into `DefaultAgentExecution`.
3. `DefaultAgentExecution` builds `DefaultExecutionStateStore` with the execution checkpoint store.
4. `DefaultExecutionStateStore.checkpoint(executionId, AgentContext)` creates the runtime's `ExecutionCheckpoint` and saves it through `ExecutionCheckpointStore`.
5. Separately, `DefaultAgentExecution` saves raw `AgentContext` snapshots through the runtime-SPI `CheckpointStore` for compatibility/resume.

This is not merely duplicate naming: the two runtime stores persist different payload types and are used in parallel. A consolidation must preserve the distinction between an agent-context recovery snapshot and a lifecycle/phase checkpoint, or deliberately combine them into a versioned envelope.

## 3. Lifecycle and phase

| Concept | Source | Consumer |
|---|---|---|
| `ExecutionPhase` | Runtime `execution/ExecutionPhase.java` | `DefaultAgentExecution` and `DefaultExecutionStateStore` record INPUT, MEMORY, INFERENCE, CONTEXT, TOOL, COMPLETE and other phases |
| Runtime `ExecutionLifecycle` record | Runtime `execution/lifecycle/ExecutionLifecycle.java` | Further consumer mapping required |
| Runtime transition rules | Runtime `ExecutionLifecycleRules.java` | `DefaultExecutionStateStore.transitionStatus` validates status changes |
| Framework `ExecutionLifecycle` enum | Framework execution API | Invocation lifecycle contract; runtime-core consumer mapping not yet established |

Do not merge processing phase and lifecycle status. The runtime's phase records where work is occurring; status records whether an execution is pending, running, paused, completed, failed or cancelled.

## 4. Follow-up searches required

- Search all modules for imports and FQNs of both `ExecutionContext` definitions.
- Search all modules for implementations/injections of each checkpoint interface, including CDI producers and test doubles.
- Search state persistence, serialization and event replay for checkpoint payload readers.
- Inspect consumers of runtime lifecycle record and framework lifecycle enum.
- Resolve Maven dependency tree for runtime-core and compile both repositories from clean checkouts.

## 5. Confirmed risks worth addressing

1. **Duplicate FQN:** two incompatible framework `ExecutionContext` interfaces are both reachable from runtime-core's declared dependency graph.
2. **Parallel recovery paths:** runtime stores both AgentContext snapshots and runtime lifecycle checkpoints; their roles and retention policy need explicit documentation.
3. **Linear context returns null graph objects:** runtime's `SimpleExecutionContext` implements the graph-oriented context and returns null for graph/currentNode. A typed linear context would be safer.
4. **Lifecycle transition enforcement:** framework `DefaultExecutionContext.transition` currently writes the new enum value directly via `AtomicReference.set`; it does not enforce the legal transition rule described by the interface documentation.
5. **In-memory store consistency:** framework execution checkpoint in-memory store checks sequence monotonicity but its read-check-add sequence is not atomic across concurrent writers. Add a concurrency test or synchronize per execution before relying on ordering.

## Scope and limitations

The map is grounded in the listed source files and POMs on this branch. It is not a full import/reference index, a Maven effective dependency tree, or a test/build report. "Not yet mapped" means no direct consumer was confirmed in this pass, not proof that none exists.
