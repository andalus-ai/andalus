# Phase 5.1 — Distributed Agent Runtime Registry

Status: implementation design; no distributed persistence implementation is claimed by this document.

## Goal

Allow multiple Andalus runtime instances to publish and discover agent availability through one shared store without allowing an old process to overwrite or revoke a newer process's registration.

This is the runtime-side persistence and lifecycle integration. The framework owns portable discovery and lease contracts; the runtime owns SQL, database dialects, CDI wiring, migrations, and operational configuration.

## Scope and non-goals

In scope:

- Shared, tenant-scoped agent runtime registrations.
- Atomic owner takeover and monotonically increasing fencing generations.
- Lease renewal and expiry evaluated using database-authoritative time.
- Fenced lifecycle updates and revocation.
- Runtime discovery that excludes expired or unavailable registrations.
- Integration and concurrency tests using two independent provider instances.

Not in scope:

- Execution/task leases. Those remain governed by the framework `FencedLeaseManager` contract and its persistence implementations.
- Agent definition/catalog persistence.
- Exactly-once task dispatch or distributed scheduling.
- Treating directory visibility as authorization. The runtime must still authorize each invocation.

## Identity and persisted record

A registration is addressed by the composite key:

`(tenant_id, agent_id, runtime_instance_id)`

The persisted record contains:

| Field | Meaning |
|---|---|
| tenant_id | Trusted tenant identity; never inferred from an agent payload |
| agent_id | Stable logical agent identity |
| runtime_instance_id | Unique process/instance identity, regenerated on process replacement |
| owner_id | Current registration owner |
| generation | Monotonically increasing fencing generation |
| state | STARTING, AVAILABLE, UNAVAILABLE, or REVOKED |
| capabilities | Versioned, bounded capability representation or normalized child rows |
| lease_expires_at | Lease deadline according to database time |
| updated_at | Database timestamp of last accepted mutation |

Do not use a process-local counter as the generation source. A process restart must not reset fencing.

## Required store semantics

The runtime persistence adapter must expose atomic operations equivalent to:

1. **Acquire/register** — create a registration or take over an expired registration. A successful acquisition returns the new owner and strictly newer generation.
2. **Renew** — extend only when tenant, agent, runtime instance, owner, generation, and unexpired lease all match.
3. **Publish state** — accept lifecycle state/capability updates only for the current, unexpired owner and generation.
4. **Revoke** — mark unavailable/revoked only for the exact current owner and generation. A stale stop event is a no-op/rejected result.
5. **Discover** — return only AVAILABLE registrations whose lease is unexpired according to the store's clock and whose visibility is permitted by the framework directory contract.

Every mutating operation must report whether the compare-and-set succeeded. A missing row, expired lease, wrong owner, or stale generation must never be interpreted as success.

## Transaction and fencing algorithm

The database is the serialization point:

- Acquire a transaction and lock the registration key (or use an equivalent atomic conditional-write strategy).
- Read the current row and database time in the same transaction.
- If a live owner exists, reject acquisition.
- If no live owner exists, allocate a generation greater than the last persisted generation and write the new owner/deadline.
- Commit before reporting acquisition success.

For databases with row-level locking, use a dialect-specific implementation of `SELECT ... FOR UPDATE`. For an insert race, rely on a unique constraint over the registration key and retry the transaction after a duplicate-key conflict. Do not silently fall back to an uncoordinated read-then-write.

Renewal, state publication, and revocation use conditional updates matching the exact owner and generation and requiring the lease to remain live according to database time. A stale generation must affect zero rows.

Generation history must survive revocation and expiry. Do not delete the row on stop: retain the last generation (or retain a separate durable generation row) so a later acquisition cannot reuse an old token.

## Time semantics

- Lease creation, renewal, takeover, and expiry checks use database-authoritative time.
- Do not compare a JVM-generated `Instant.now()` against a database deadline for correctness decisions.
- Inject a database time source/dialect strategy; do not assume every database supports identical timestamp expressions.
- Tests use a controllable database clock where available, or a narrowly scoped SQL time-source abstraction. Avoid `Thread.sleep` for expiry assertions.
- Document maximum tolerated database clock behavior and connection/transaction timeout requirements.

## Lifecycle integration

The lifecycle adapter must map events as follows:

| Runtime lifecycle event | Registry operation |
|---|---|
| Process starts | Acquire registration as STARTING |
| Agent becomes ready | Publish AVAILABLE and capabilities |
| Periodic heartbeat | Renew exact owner/generation lease |
| Agent becomes unhealthy | Publish UNAVAILABLE while lease is still owned |
| Graceful stop | Fenced REVOKED update |
| Process crash | No event; lease naturally expires |

A host must publish AVAILABLE only after the agent is actually ready. Heartbeats must be periodic and comfortably shorter than the lease duration. A failed renewal means ownership is lost: stop publishing availability and do not attempt an unfenced revoke.

A restarted process obtains a new instance identity and generation. Delayed callbacks from the old process cannot mutate the new registration.

## Discovery and tenant safety

- Use the framework directory's visibility semantics as the first discovery filter.
- Filter runtime records by trusted tenant scope and AVAILABLE/unexpired state.
- Never treat visibility metadata as an authorization decision.
- Do not accept tenant identity from a discovery query as trusted authority without validating it against the authenticated security context.
- Avoid returning expired registrations and then relying on the caller to filter them.
- Define deterministic ordering for duplicate logical agent IDs and reject ambiguous active ownership rather than selecting arbitrarily.

## Schema and migration requirements

The first SQL migration must include:

- A primary/unique key for the registration identity.
- Non-null tenant, agent, runtime instance, owner, generation, state, and expiry columns.
- A check constraint for positive generations where supported.
- An index supporting tenant + state + expiry discovery.
- A versioned capability representation (or normalized capability table with a foreign key).
- Retention of generation after revoke/expiry.

Keep SQL types portable where practical. Database-specific locking and time expressions belong behind dialect-specific implementations, not in the framework contract.

## Failure handling

| Failure | Required behavior |
|---|---|
| Database unavailable during acquire | Fail registration; do not advertise availability |
| Database unavailable during renewal | Treat ownership as uncertain/lost; stop advertising |
| Renewal rejected | Stop publishing and cancel local registration heartbeat |
| Old owner stops after takeover | Reject stale revoke by generation |
| Two instances acquire simultaneously | Exactly one acquisition succeeds |
| Process crashes | Registration becomes undiscoverable after lease expiry |
| Capability update fails | Keep previous committed state; do not publish partial data |
| Tenant mismatch | Reject before reading or mutating another tenant's record |

The registry provides leased availability, not guaranteed agent health or task delivery. Consumers must tolerate an agent becoming unavailable after discovery.

## Test matrix

The runtime adapter is not production-ready until these tests run against the intended database(s):

1. Two independent provider instances race to acquire the same registration; one wins.
2. A lease expires; a second instance takes over with a strictly higher generation.
3. The old owner renews after takeover; renewal is rejected.
4. The old owner publishes AVAILABLE after takeover; update is rejected.
5. The old owner sends STOPPED after takeover; the successor remains AVAILABLE.
6. A process crashes; discovery excludes it after expiry.
7. Two tenants can register the same agent ID without seeing or mutating each other's state.
8. Concurrent renewal and takeover cannot both succeed for the same generation.
9. Database clock boundary tests cover just-before, exactly-at, and just-after expiry.
10. Restarted instances cannot reuse an earlier generation.

Use deterministic barriers/latches and database transactions rather than timing sleeps for race tests.

## Delivery sequence

1. Confirm the canonical framework SPI and its semantics; extend the framework first only if a required portable operation is genuinely missing.
2. Implement the runtime JDBC store and migration behind that SPI.
3. Add database-time and locking dialect support for the databases officially supported by Andalus.
4. Wire CDI lifecycle publication through the store abstraction, retaining in-memory mode for local/test deployments.
5. Add readiness/health signals for registration renewal failures and expired active registrations.
6. Add two-instance integration tests and operational documentation.

## Acceptance criteria

- Two runtime instances coordinate through a shared durable store.
- A successful takeover always receives a newer generation.
- Stale owners cannot renew, publish state, or revoke successors.
- Expired registrations are excluded from discovery using database time.
- Tenant isolation is enforced in every query and mutation.
- Database failures fail closed for availability publication.
- In-memory mode remains explicitly process-local and is not described as distributed.
- Execution leases and agent-registration leases remain separate concepts.
