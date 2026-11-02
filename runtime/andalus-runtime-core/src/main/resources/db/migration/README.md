# Runtime database migrations

Apply the SQL files in this directory with the deployment's chosen migration
runner before enabling the corresponding runtime feature.

## Durable admitted-feedback ledger

Apply both migrations in order:

- `V20261102_01__feedback_evidence_reservations.sql`
- `V20261102_02__admitted_feedback_ledger.sql`

The second migration creates `andalus_admitted_feedback`, a typed provenance
record for each accepted human-feedback signal. The evidence-reference rows
remain normalized in `andalus_feedback_evidence`. Runtime admission writes the
signal record and its complete evidence reservation in one JDBC transaction;
a failure in either insert rolls back both. Exact retries with the same signal
payload and evidence batch are idempotent; changed payloads or reused evidence
are rejected.

Only bounded, typed provenance is stored: signal ID/type, source, execution,
polarity, optional turn/goal/task/evaluation references, timestamp, and
normalized evidence references. The arbitrary `LearningSignal.metadata` map
is intentionally excluded. `JdbcAdmittedFeedbackLedger.findAll()` recovers
signals in deterministic timestamp/ID order after adapter or process restart.

## Feedback evidence reservations

The first migration creates `andalus_feedback_evidence`. Its primary key on
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

Both migrations currently use broadly supported `CREATE TABLE IF NOT EXISTS`
syntax and are exercised by H2 tests. Validate them against the production
database vendor and its supported transactional DDL behavior before rollout.

## Readiness and fail-closed behavior

The runtime publishes a MicroProfile readiness check named
`feedback-admitted-ledger`. It reports DOWN when no datasource is configured,
the database cannot be reached, or either required migration is absent.
The probe performs a read-only zero-row query; it never creates the table and
does not expose database exception details in health output.

Feedback reservation itself also fails closed: JDBC errors are surfaced as an
exception and no in-memory fallback is selected. Deployments should require
this readiness check to be UP before routing feedback-admission traffic. The
health check is an operational signal, not a replacement for deployment
ordering or caller-side error handling.

## Admission boundary

Use the runtime `FeedbackAdmissionService` as the entry point for human
feedback. It verifies both required tables, evaluates the framework trust
policy, then atomically reserves evidence and persists the admitted signal. If the
datasource is missing, ambiguous, unreachable, or the migration is absent, it rejects admission with
a bounded reason and does not create schema or select an in-memory fallback.

The service returns the framework's `AdmissionResult`; rejected trust-policy
decisions and evidence replays remain rejected by the framework contract.
Accepted feedback is recoverable from the durable ledger and only made
available as an advisory aggregation input.
This path does not train models, alter routing, promote candidates, or deploy
configuration. Callers should not bypass this service in production ingestion
paths.
