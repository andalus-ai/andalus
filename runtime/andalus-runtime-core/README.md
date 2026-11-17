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
fencing, and concurrent acquisition; PostgreSQL integration coverage remains
required before claiming cross-database production readiness.

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
