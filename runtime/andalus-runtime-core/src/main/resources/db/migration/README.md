# Runtime database migrations

Apply the SQL files in this directory with the deployment's chosen migration
runner before enabling the corresponding runtime feature.

## Agent runtime-state leases

Apply `V20261105_01__agent_runtime_state_leases.sql` before configuring
`JdbcAgentRuntimeStateProvider`. The migration creates a shared table keyed by
`(tenant_id, agent_id)`; that key serializes each agent's live routing state
across runtime instances.

Every runtime instance must use the same durable database and the lifecycle
coordinator must use a shared `FencedLeaseManager` to allocate authoritative,
monotonic generations. The JDBC provider rejects stale generations, rejects
same-generation changes of owner or state, and compares expiry against the
database clock. A concurrent first publication that loses the primary-key
race returns `false`; retry only after confirming current ownership. JDBC
errors fail closed; this provider never falls back to in-memory state.

The runtime module does not bundle a migration engine. Apply the migration
through the deployment's migration process and validate its DDL against the
production database vendor before rollout. Keep
`InMemoryAgentRuntimeStateProvider` for single-process development and tests.

## Feedback evidence reservations

Apply:

`V20261102_01__feedback_evidence_reservations.sql`

The migration creates `andalus_feedback_evidence`. Its primary key on
`evidence_reference` and unique constraint on
`(signal_id, reference_order)` are correctness constraints, not merely
performance indexes: they prevent two processes from consuming the same
evidence and prevent a signal identifier from being reused with a different
ordered batch.

The runtime module deliberately does not select or bundle a migration engine.
Wire this SQL into the deployment's existing migration process and ensure it
runs before feedback admission is enabled. Do not rely on
`JdbcFeedbackEvidenceStore.initializeSchema()` in production; that method is
only a convenience bootstrap for tests and local development.

The migration currently uses broadly supported `CREATE TABLE IF NOT EXISTS`
syntax and has an H2 test. Validate it against the production database vendor
and its supported transactional DDL behavior before rollout.

## Readiness and fail-closed behavior

The runtime publishes a MicroProfile readiness check named
`feedback-evidence-store`. It reports DOWN when no datasource is configured,
the database cannot be reached, or the feedback evidence migration is absent.
The probe performs a read-only zero-row query; it never creates the table and
does not expose database exception details in health output.

Feedback reservation itself also fails closed: JDBC errors are surfaced as
an exception and no in-memory fallback is selected. Deployments should require
this readiness check to be UP before routing feedback-admission traffic. The
health check is an operational signal, not a replacement for deployment
ordering or caller-side error handling.

## Admission boundary

Use the runtime `FeedbackAdmissionService` as the entry point for human
feedback. It verifies the required schema before invoking the framework trust
policy and atomic evidence reservation. If the datasource is missing,
ambiguous, unreachable, or the migration is absent, it rejects admission with
a bounded reason and does not create schema or select an in-memory fallback.

The service returns the framework's `AdmissionResult`; rejected trust-policy
decisions and evidence replays remain rejected by the framework contract.
Accepted feedback is only made available as an advisory aggregation input.
This path does not train models, alter routing, promote candidates, or deploy
configuration. Callers should not bypass this service in production ingestion
paths.
