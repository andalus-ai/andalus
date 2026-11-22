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

`AgentRuntimeRegistrationTelemetry` is an application-scoped in-process aggregate with event counters, renewal count/total/average/maximum latency, and last-success/last-failure timestamps. Its snapshot deliberately omits tenant, agent, instance, and owner labels to avoid high-cardinality metrics and identity leakage. Hosts can export the snapshot through their existing metrics stack. Pass the telemetry bean (or another observer) to the observer-aware `AgentRuntimeRegistrationSession.open(...)` overload; the default overload remains a no-op for compatibility. The telemetry bean does not install an exporter or deliver alerts. Configure alert thresholds in the host, for example on sustained renewal errors/rejections or an aged last-success timestamp.

These registration tokens are not `FencedLeaseManager` execution/task tokens.
The older `AgentRuntimeLifecycleCoordinator` and
`AgentRuntimeLifecycleLeaseObserver` remain the legacy lifecycle projection
path; they must not be used as a source of registration ownership or as a
bridge that copies execution-lease generations into registration records.

## Registration readiness and recovery (Phase 5.1.9)

`AgentRuntimeRegistrationReadiness` exposes an opt-in MicroProfile readiness check. Its initial `NOT_CONFIGURED` state is UP so applications that do not enable distributed registration are unaffected. Once the host starts registration, `STARTING`, `DEGRADED`, and `STOPPED` report DOWN; only `READY` reports UP. This check is readiness, not liveness.

`AgentRuntimeRegistrationSupervisor` is an explicit host integration boundary. Construct it with the host-managed `ScheduledExecutorService`, registration identity, heartbeat interval, and bounded retry delays. Call `start()` during host startup, then `markAvailable()` only after local dependencies and agent initialization are ready. Call `markUnavailable()` when the agent is intentionally withdrawn from routing, and `close()` during shutdown before the host terminates its scheduler.

On rejected renewal, store failure, or rejected status publication, the supervisor marks readiness DOWN and schedules bounded exponential reacquisition. Every recovery calls the registration store's acquire operation and therefore obtains a new generation; the failed token is never reused. If the host had already called `markAvailable()`, the supervisor republishes AVAILABLE after a new session is acquired. If the host had marked the agent unavailable, recovery acquires a STARTING registration and waits for the host to call `markAvailable()` again. A database outage therefore keeps the instance unready until registration can be proven again.

The supervisor does not own threads, shut down the scheduler, perform process liveness checks, or authorize invocation. The host remains responsible for local dependency readiness, startup/shutdown ordering, and ensuring that the supervisor is closed before its scheduler. Retry delay is capped by the configured maximum. Hosts should size the retry maximum and alert thresholds against their registration lease duration and service recovery objectives.



## Recovery scheduler failure observability (Phase 5.1.16)

The observer event `RECOVERY_SCHEDULING_REJECTED` distinguishes rejection of the supervisor's recovery task from heartbeat scheduling failure (`SCHEDULING_ERROR`) and registration-store failures (`RENEWAL_ERROR` / `RENEWAL_REJECTED`). `AgentRuntimeRegistrationTelemetry` counts this event and updates its last-failure timestamp, allowing hosts to alert specifically when the host-owned scheduler cannot accept recovery work.

A recovery scheduling rejection leaves readiness DEGRADED and the lost generation unavailable through `token()`. The runtime does not create a replacement scheduler or start background threads. The embedding host must restore scheduler availability and explicitly reconcile/restart the supervisor according to its lifecycle policy. Observer exceptions are isolated and cannot change registration ownership or readiness.

## Multi-agent PostgreSQL recovery validation (Phase 5.1.12)

`AgentRuntimeRegistrationGroupPostgresTest` exercises the multi-agent group
against PostgreSQL 16 through Testcontainers and the production JDBC store.
It expires one agent using PostgreSQL's `CURRENT_TIMESTAMP`, then verifies
that the supervisor reacquires a higher generation and restores AVAILABLE
without changing a sibling agent's token or status. A second scenario verifies
that the same logical agent ID in separate tenants remains isolated.

The test uses a host-owned scheduled executor and closes the registration
group before shutting down that executor. It is disabled automatically when
Docker is unavailable, consistent with the existing PostgreSQL store tests.
This is integration coverage for recovery and tenant isolation; it does not
replace deployment migration validation or multi-process load testing.

## Multi-agent registration groups (Phase 5.1.11)

For a process hosting multiple independently registered agents, use
`AgentRuntimeRegistrationGroup` instead of creating multiple instances of the
single-agent CDI bootstrap. Construct the group with the shared
`AgentRuntimeRegistrationLifecycle` and host-owned
`ScheduledExecutorService`, then call `register(Registration)` once per
trusted host identity. Each entry receives its own supervisor, readiness
state, heartbeat, recovery loop, and fenced lease token. The group does not
create threads or infer identity.

Use the returned `Key(tenantId, agentId, instanceId)` with
`markAvailable`, `markUnavailable`, and `unregister`. Duplicate keys are
rejected. Removing one entry closes only that supervisor; closing the group
closes all remaining supervisors before the host shuts down its scheduler.
`isReady()` is true only when the group is non-empty and every registered
agent is READY. Hosts should expose this aggregate signal through their
application readiness integration and may use `states()` to report per-agent
diagnostics. One agent becoming degraded does not change the readiness state
or lease of another agent, although it makes the aggregate group unready.

The group is a programmatic host integration boundary, not an automatic
replacement for the single-agent `AgentRuntimeRegistrationBootstrap`.
Applications with multiple agents must own registration ordering, dynamic
add/remove calls, aggregate health exposure, and group shutdown explicitly.

## CDI host integration (Phase 5.1.10)

The runtime provides an opt-in Quarkus/CDI adapter through `AgentRuntimeRegistrationBootstrap`. It is inert when no `AgentRuntimeRegistrationHost` bean is supplied. An opting-in deployment must provide exactly one host identity bean and exactly one CDI `ScheduledExecutorService`; the runtime also requires its registration lifecycle and readiness beans. Missing or ambiguous required dependencies fail startup instead of silently disabling registration.

Implement `AgentRuntimeRegistrationHost` in the embedding application and return one `Registration` containing the tenant, logical agent, process instance, owner identity, heartbeat interval, and bounded retry delays. These identities are deliberately not inferred by the runtime. The current bootstrap contract is one logical agent registration per process; deployments hosting multiple independently registered agents should manage a supervisor per identity and aggregate readiness explicitly rather than reusing this single-registration adapter.

The bootstrap observes Quarkus `StartupEvent` and starts a STARTING registration. The host must call `markAvailable()` only after its own agent initialization and local dependency checks succeed. Call `markUnavailable()` when the process remains alive but should stop receiving agent traffic. The adapter exposes `isReady()` and the current fenced `token()` for host diagnostics/integration. It observes `ShutdownEvent` and closes the supervisor, cancelling its heartbeat/recovery tasks and attempting fenced revocation.

The embedding application owns the scheduler bean and must keep it alive until registration shutdown has completed. Do not shut the scheduler down before the runtime's `ShutdownEvent` observer has closed the bootstrap. The adapter creates no threads, does not mark the agent AVAILABLE automatically, and does not grant invocation authorization. Registration store schema/migrations remain deployment-managed. When registration is not configured, the readiness check remains `NOT_CONFIGURED` and reports UP.

Example host provider (identity values must come from the embedding application's trusted configuration/identity system):

```java
@ApplicationScoped
public class MyAgentRegistrationHost implements AgentRuntimeRegistrationHost {
    @Override
    public Registration registration() {
        return new Registration(
                tenantId(), agentId(), processInstanceId(), ownerId(),
                Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(30));
    }
}
```

The application must also expose its managed scheduler as a CDI bean. Inject `AgentRuntimeRegistrationBootstrap` into the component that knows when the agent is locally ready and call `markAvailable()` at that point. A successful process startup alone is not sufficient to publish AVAILABLE.
