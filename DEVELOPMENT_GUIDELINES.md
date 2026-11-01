# Andalus Runtime Development Guidelines

These guidelines describe the intended engineering practices for the runtime repository. They complement the root `AGENTS.md`.

## 1. Product and repository boundary

Andalus is a modular agent platform. The runtime repository is responsible for turning framework capabilities into an operational product.

The runtime should own:
- Concrete agent/workflow/skill execution services and runtime lifecycle orchestration.
- Provider, tool, storage, messaging, graph, vector and other infrastructure adapters.
- Quarkus/CDI integration, REST/gRPC endpoints, CLI/TUI applications and deployment packaging.
- Operational concerns such as health checks, configuration binding, metrics, tracing, persistence and worker execution.

The framework repository should own reusable contracts and infrastructure-independent capabilities. A runtime module may implement a framework SPI, but should not fork or redefine that SPI.

## 2. Module design

Every module should have a clear reason to exist and a narrow public surface.

For each module, document:
- Responsibility and non-responsibilities.
- Public API packages versus internal implementation packages.
- Allowed upstream dependencies.
- Runtime/infrastructure assumptions.
- How it is tested and which optional services it needs.

Rules:
- Keep module dependencies directed and acyclic.
- Depend on the smallest required module, not an umbrella module, when possible.
- Avoid cross-module access to implementation packages.
- Keep adapters at the edge; keep domain and execution rules independent of transport.
- Do not introduce a new module solely to relocate a few classes unless it creates a meaningful ownership or dependency boundary.
- Before extracting or merging modules, inspect actual consumers and public artifact usage.

## 3. Framework/runtime contract

Before introducing a concept such as execution state, lifecycle, checkpoint, event, cancellation, policy, memory or tool invocation:
1. Search the framework for an existing contract.
2. Search the runtime for existing implementations and legacy contracts.
3. Decide which repository owns the abstraction.
4. Define one canonical contract and explicit adapters for legacy behavior.
5. Add cross-repository contract tests and document version compatibility.

The framework should define stable semantics. The runtime may define persistence models, CDI beans, queues, database schemas and operational policies. Do not expose runtime implementation types through framework public APIs.

## 4. Execution lifecycle and durability

Execution behavior must be explicit and testable.

- Define legal lifecycle transitions and reject illegal transitions.
- Specify whether a transition is in-memory, persisted, emitted as an event, or all three.
- Make checkpoint writes atomic with respect to the state they represent.
- Use stable execution and checkpoint identifiers.
- Define resume semantics, including how the runtime handles stale checkpoints and changed agent definitions.
- Ensure retries and message redelivery do not duplicate non-idempotent external side effects.
- Propagate cancellation, deadlines, tenant identity, user identity and security context.
- Keep human approval as an explicit wait/resume boundary; never treat approval as a normal successful tool result.

## 5. Provider and tool integrations

- Implement integrations behind framework-defined interfaces where available.
- Isolate vendor-specific request/response models in adapter modules.
- Apply timeouts, bounded retries, backoff and circuit breaking where appropriate.
- Make retryability explicit; do not retry authorization, validation or other permanent failures blindly.
- Validate tool arguments and authorize the caller before execution.
- Apply least privilege and sandbox untrusted code or tools.
- Record tool identity, version, input/output references and outcome in audit events without leaking sensitive payloads.

## 6. APIs, events and persistence

- Keep REST/gRPC transport DTOs separate from framework domain contracts and persistence entities.
- Version externally consumed APIs and event schemas.
- Define event ordering, delivery guarantees, deduplication keys and retention behavior.
- Use migrations for persistent schema changes; test upgrade paths.
- Do not serialize arbitrary Java object graphs as long-lived checkpoints. Prefer versioned, explicit schemas.
- Do not expose database entities as public API responses.

## 7. Security, tenancy and governance

- Enforce tenant isolation at service, storage, cache, event and tool boundaries.
- Resolve identity and authorization from trusted authentication context, not request-supplied identifiers alone.
- Store secrets in approved secret providers; never commit secrets or place them in logs.
- Treat prompts, retrieved documents, tool outputs and agent-generated plans as untrusted input.
- Require explicit policy checks for high-impact tools and external side effects.
- Audit policy decisions, approvals, secret access and administrative actions with correlation identifiers.

## 8. Observability and operations

- Propagate trace and correlation identifiers across HTTP, gRPC, messaging, tool calls and model-provider calls.
- Emit structured logs and bounded-cardinality metrics.
- Capture latency, retries, token usage/cost where available, tool outcomes, queue age, checkpoint failures and lifecycle transitions.
- Avoid metric labels containing tenant IDs, user IDs, prompts or other unbounded/sensitive values.
- Provide health/readiness checks that distinguish process health from dependency readiness.
- Make graceful shutdown and in-flight execution behavior explicit.

## 9. Testing strategy

Use a testing pyramid:
- Unit tests for state machines, policy decisions, mapping and pure logic.
- Contract tests for framework SPIs and runtime implementations.
- Integration tests for databases, queues, providers and Quarkus wiring.
- End-to-end tests for representative agent, workflow and human-approval paths.

Include negative cases: invalid transitions, cancellation, timeout, unavailable providers, malformed tool input, duplicate events, stale checkpoints, tenant boundary violations and partial failures.

Tests must be deterministic. Use fake clocks, deterministic IDs and stub providers where appropriate. Keep live-provider tests opt-in and clearly separated from normal CI.

## 10. Build, dependencies and releases

- Use the Maven Wrapper and the Java version declared by the parent POM.
- Keep dependency versions centralized and review transitive dependency changes.
- Keep runtime and framework artifact versions aligned through an explicit release compatibility policy.
- Verify both repositories together when changing public framework contracts.
- Do not assume that a module omitted from the reactor is obsolete; document its status and build requirements before changing reactor membership.
- Verify publishing repository URLs and credentials through CI configuration; never embed credentials in POMs.
- Prefer reproducible builds and immutable release artifacts.

## 11. Documentation and pull requests

A change should explain the problem, design, compatibility impact, tests and operational impact.

For a new module, include a README with purpose, API, dependencies, configuration, examples and test instructions. For public API or event changes, include migration notes. For architectural decisions, record the decision and rejected alternatives.

A pull request should:
- Be focused and reviewable.
- Include tests and documentation.
- Identify cross-repository dependencies.
- Report exact verification commands and outcomes.
- Call out known gaps rather than presenting incomplete work as production-ready.

## 12. Current architectural focus

The first cross-repository improvement priority is to establish one authoritative ownership model for execution lifecycle, execution context, checkpoint/state contracts, and orchestration. Audit existing contracts and runtime implementations before introducing replacements. Preserve compatibility through explicit adapters and a staged migration rather than a flag-day rewrite.
