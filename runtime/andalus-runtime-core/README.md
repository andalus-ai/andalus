# Andalus Runtime Core

Core execution and coordination primitives for Andalus agents.

## Capability-based delegation

The coordination discovery package exposes a runtime SPI and planning path:

- `AgentCapabilitySource` lets a runtime module advertise candidates for a
  specific coordination request and context.
- `AgentCapabilityDirectory` aggregates CDI-discovered sources, rejects
  duplicate agent ids, and returns stable id ordering.
- `AgentDelegationPlanner` combines discovery with
  `CapabilityBasedAgentSelector` to produce ranked delegation candidates.

Candidate metadata uses stable namespaced capability identifiers such as
`research.web-search`. Required capabilities are hard filters; preferred
capabilities improve ranking; availability and optional cost ceilings are
respected. Ties are resolved by configured priority, cost, then agent id.

This path plans a target only. Capability advertisements do not grant
authorization. Coordinators must still enforce tenant visibility, policy,
execution budgets, and dispatch rules before sending work to a candidate.


## Coordination plan validation

`CoordinationPlanValidator` checks a plan before compilation or dispatch:

- participating agents are declared, unique, and within `CoordinationPolicy.maxAgents`;
- every step has a unique id and targets a declared agent;
- dependencies reference existing steps without duplicates or self-references;
- the dependency graph is acyclic.

Validation is structural only. It does not grant permissions, establish tenant visibility,
reserve execution budget, or dispatch work; those checks remain mandatory at the execution boundary.


## Distributed agent runtime registration (Phase 5.1)

`JdbcAgentRuntimeRegistrationStore` implements the framework-owned
`AgentRuntimeRegistrationStore` contract using the application's `DataSource`.
It serializes ownership by `(tenant_id, agent_id, instance_id)`, uses row locks
for mutations, increments a durable generation on takeover, and evaluates
expiry against the database's `CURRENT_TIMESTAMP`. Mutations compare owner and
generation so an older process cannot renew, publish state, or revoke its
successor.

Before enabling the adapter, apply
`META-INF/andalus/agent-runtime-registration-schema.sql` to the runtime
 database. The adapter intentionally does not create or migrate tables during
application startup; migration execution remains owned by deployment/database
operations. Production deployments must provide a shared transactional JDBC
DataSource. The included H2 tests exercise lifecycle, tenant isolation, expiry,
fencing, and concurrent acquisition; PostgreSQL integration coverage is provided by
`JdbcAgentRuntimeRegistrationPostgresTest`: it starts PostgreSQL 16 through
Testcontainers and creates two independent store instances backed by separate
DataSources. It verifies cross-instance visibility, database-clock expiry
takeover, generation fencing, heartbeat renewal, and simultaneous acquisition.
Maven Surefire discovers it as a normal `*Test`; it skips automatically when
Docker is unavailable and runs in Docker-enabled CI environments.

## Shared runtime registration-backed discovery

`RegisteredAgentCapabilitySource` is the distributed-discovery path for deployments
using `AgentRuntimeRegistrationStore`. It first applies
`AgentDirectory.findVisibleToTenant(tenantId)`, then includes a logical agent
only when the shared registration store reports at least one live
`AVAILABLE` runtime instance for that same tenant and agent. Multiple ready
instances collapse to one logical candidate; STARTING, UNAVAILABLE, REVOKED,
expired, and missing registrations are excluded.

This source deliberately fails closed when the tenant is absent or the
registration store is missing/ambiguous. It does not fall back to process-local
runtime state. Registration is liveness/readiness metadata only: the dispatch
boundary must still enforce invocation authorization and policy.

The legacy `DirectoryAgentCapabilitySource` remains available for deployments
that explicitly use the older runtime-state-provider path. Do not enable both
sources for the same agent population, because the aggregation directory
rejects duplicate logical agent IDs.

## Registration lifecycle integration

`AgentRuntimeRegistrationLifecycle` is the host-facing lifecycle facade for
the shared registration SPI. Runtime startup calls `start(...)` and retains
the returned registration token; readiness calls `markAvailable(token)`;
degradation calls `markUnavailable(token)`; a host-managed heartbeat calls
`heartbeat(token)`; graceful shutdown calls `shutdown(token)`.

For hosts that want a reusable lifecycle boundary, `AgentRuntimeRegistrationSession.open(...)`
acquires the registration and schedules heartbeats on a caller-supplied
`ScheduledExecutorService`. The supplied scheduler remains host-owned: the
session neither creates nor shuts it down. The session retains the exact token,
exposes a local `isRoutable()` readiness view, and marks itself non-routable
immediately when renewal is rejected. It cancels the heartbeat before fenced
revocation on close. A failed/lost registration cannot be made available again
through that session; the host must create a new session and acquire a new
generation. Heartbeat intervals must be positive, at least one millisecond,
and shorter than the registration lease duration.

The facade intentionally does not create a scheduler, executor, or background
thread. The embedding host owns heartbeat cadence, cancellation, and shutdown
ordering. A heartbeat should be scheduled comfortably inside the configured
lease duration and treated as failed when the store returns an empty result;
the host must then stop advertising the instance and must not continue using
the stale token. The registration store's clock is authoritative.

## Registration telemetry (Phase 5.1.8)

`AgentRuntimeRegistrationObserver` is a runtime-owned, dependency-neutral event hook. The session reports acquisition, readiness changes, successful renewals, rejected renewals, store exceptions, scheduling failures, and fenced shutdown outcomes. A rejected lease and a database/store exception are distinct events; both make the session non-routable and stop renewal. Recovery requires a new session and a newly acquired generation—this session never retries a stale token.

`AgentRuntimeRegistrationTelemetry` is an application-scoped in-process aggregate with event counters and last-success/last-failure timestamps. Its snapshot deliberately omits tenant, agent, instance, and owner labels to avoid high-cardinality metrics and identity leakage. Hosts can export the snapshot through their existing metrics stack. Pass the telemetry bean (or another observer) to the observer-aware `AgentRuntimeRegistrationSession.open(...)` overload; the default overload remains a no-op for compatibility. The telemetry bean does not install an exporter or deliver alerts. Configure alert thresholds in the host, for example on sustained renewal errors/rejections or an aged last-success timestamp.

These registration tokens are not `FencedLeaseManager` execution/task tokens.
The older `AgentRuntimeLifecycleCoordinator` and
`AgentRuntimeLifecycleLeaseObserver` remain the legacy lifecycle projection
path; they must not be used as a source of registration ownership or as a
bridge that copies execution-lease generations into registration records.
