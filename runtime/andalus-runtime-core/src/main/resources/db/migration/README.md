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

## Advisory aggregation snapshots

Apply `V20261102_03__feedback_aggregation_snapshots.sql` after the two
admitted-feedback migrations. It creates an append-only snapshot header and a
normalized item table for signal IDs, sources, evidence references, and
aggregation reasons.

`FeedbackAggregationRecoveryService.aggregateAndSnapshot(...)` rebuilds the
advisory result from the current durable ledger and appends a snapshot in one
transaction. `history(subject)` reads prior snapshots for the exact typed
subject. Each snapshot stores the outcome, weighted totals and ratios, counts,
trust/aggregation/corroboration fingerprints, contributing and considered
signal IDs, sources, evidence references, reasons, and creation timestamp.
Repeated evaluations create separate history entries; they do not overwrite
earlier conclusions. History is ordered by creation time, with snapshot ID as
a stable tie-breaker for entries created within the same millisecond.

`FeedbackAggregationRecoveryService.analyzeTrend(subject)` compares the two
newest snapshots for that exact typed subject. It reports outcome transitions,
positive/negative ratio and weight deltas, source/evidence-count deltas, and
added or removed contributing signal IDs. A positive-consensus reversal is
reported as a regression; a transition to conflicted evidence is reported as
emerging disagreement. Insufficient evidence is explicitly inconclusive, not a
regression. A configurable positive-ratio drop threshold is available for
successive positive-consensus snapshots. Trend analysis is deterministic and
advisory; it does not persist a second derived record or change production
behavior.

`FeedbackAggregationRecoveryService.reviewTrend(subject, policy)` evaluates
the latest trend against explicit review guardrails. The conservative defaults
require at least two distinct sources and two evidence references. Regression,
emerging disagreement, removed contributing signals, and configured source or
evidence count decreases can require human review. Below-minimum evidence and
insufficient history return INCONCLUSIVE rather than NO_REVIEW_REQUIRED.
Assessments include stable reason codes and the baseline/current snapshot IDs.
The assessment is computed from immutable history and is not itself persisted;
callers own any human-review workflow and its audit trail. No notification,
ticket, training, routing, promotion, or deployment is triggered.

Snapshots are audit records, not commands. They do not trigger training,
routing changes, candidate promotion, or deployment. Keep the original policy
inputs and corroboration data alongside operational change records when exact
reproduction is required; fingerprints identify policy inputs but do not
reconstruct them. The snapshot store verifies its own schema and fails closed
when the third migration is absent.

## Human feedback trend review decisions

Apply `V20261103_04__feedback_trend_review_decisions.sql` after the
aggregation snapshot migration. It creates an append-only review header and
normalized reason-code table, both linked to immutable aggregation snapshots.

Use `FeedbackTrendReviewService.record(assessment, reviewerId, disposition,
rationale)` to persist an explicit human disposition. The store verifies that
the baseline and current snapshot IDs belong to the assessment's exact typed
subject, stores the assessment decision and stable reason codes, and writes the
review header and reasons atomically. Each call appends a new review; it never
overwrites an earlier disposition. Reviewer identity and rationale are
mandatory and bounded. `history(subject)` recovers decisions in deterministic
timestamp/ID order.

Dispositions describe the human response (`ACKNOWLEDGED`, `ACCEPTED`,
`REJECTED`, or `NEEDS_MORE_EVIDENCE`); they do not approve or apply a model,
routing, policy, or deployment change. Recording a decision triggers no
notification, ticket, training, promotion, routing change, or deployment.
The review store fails closed if migration 04 is absent or a unique durable
datasource is unavailable. Apply and validate this migration with the
deployment's selected migration runner before enabling review recording.

## Proposal-specific adaptation approvals

Apply `V20261103_05__feedback_adaptation_approvals.sql` after migration 04.
It records an explicit human APPROVED or REJECTED decision for one exact
proposal ID, typed subject, source snapshot, and policy fingerprint, linked to
the persisted trend review. Approval records are append-only and retain the
reviewer, rationale, and decision timestamp.

A generic ACCEPTED trend-review disposition is not itself approval of a
learning-policy change. Use `FeedbackTrendReviewService.decideProposal(...)`
to record a separate proposal-specific decision. APPROVED can be recorded
only when the linked review is ACCEPTED and its assessment is conclusive.
`evaluateForShadow(...)` opens the gate only when the persisted approval
matches the exact proposal ID, source snapshot, policy fingerprint, subject,
and authenticated reviewer identity. The caller must obtain that identity
from a trusted security principal, not an untrusted request field.

The gate grants eligibility for shadow evaluation only. It does not authorize
production activation, routing changes, model training, candidate promotion,
or deployment. Those require separate evaluation and approval workflows.
Missing datasource or migration fails closed. Validate migration 05 against
the production database vendor and its transactional DDL behavior before use.

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

All three migrations currently use broadly supported `CREATE TABLE IF NOT EXISTS`
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


## Shadow-evaluation evidence

Apply `V20261103_06__feedback_shadow_evaluation_evidence.sql` after migration 05.
It creates an append-only run header plus normalized metric and evidence
reference tables. The report captures the exact proposal, typed subject,
source snapshot, approval ID, policy fingerprint, baseline/candidate evaluator
identities, versions and configuration fingerprints, baseline/candidate model
identities and versions, dataset
identity/version/fingerprint, metric values,
directional improvement thresholds, result, timestamp, and evidence references.
Dataset contents, prompts, and arbitrary metadata are not stored.

A shadow report's status is derived from its metrics: all metrics meeting their
directional minimum improvement yields PASS; any metric missing its threshold
yields FAIL; an empty metric set is INCONCLUSIVE. Non-finite values, duplicate
metric names, duplicate evidence references, and oversized identifiers are
rejected. Metric and evidence ordering is retained for deterministic recovery.

Use `ShadowEvaluationService.record(report, authenticatedReviewerId)` to
re-check the current exact proposal approval before persisting a report.
The authenticated reviewer must come from the trusted security principal.
The JDBC store independently verifies that the approval is APPROVED, its
linked trend review is ACCEPTED and conclusive, and every proposal identity
field matches. A run ID is immutable: an exact retry is idempotent and a
changed payload under the same ID is rejected. Header, metrics, and evidence
references are written atomically.

The runtime now defines a trusted manifest boundary before execution.
Register an immutable dataset manifest for the exact dataset ID/version and
signed evaluator-artifact manifests for each exact candidate ID/version,
evaluator ID/version, and configuration fingerprint. `ShadowEvaluationService.evaluateAndRecord(...)` requires
one unambiguous `ShadowEvaluationTrustRegistry` and one unambiguous
`ShadowEvaluationManifestVerifier`. It rejects missing manifests, changed
dataset content/case count, descriptor mismatches, and failed signature or
artifact verification before invoking either evaluator.

The verifier is a security-critical SPI, not a built-in cryptographic
implementation. Production wiring must verify signatures against a managed
trust store, validate artifact bytes against the immutable artifact digest,
and attest that each supplied evaluator adapter instance was loaded from that
verified artifact. A verifier that checks metadata only is unsafe. There is
deliberately no permissive default or in-memory fallback: without both trusted
implementations, execution fails closed. Keep signing keys, revocation policy,
artifact storage, and registry administration in the deployment's security
boundary. The current SPI does not itself provide a registry implementation,
key management, or signature algorithm.

The manifest gate verifies evaluator artifacts and the actual supplied adapter
instances, but it does not independently derive the proposal policy fingerprint.
The proposal fingerprint must continue to bind the exact candidate identity,
evaluator configuration, and metric policy approved by the reviewer. PASS is
not promotion approval: no model training, policy activation, routing change,
candidate promotion, or deployment is performed. Validate migration 06
against the production database vendor and transactional DDL behavior before
rollout.


### Paired evaluator execution

`ShadowEvaluationService.evaluateAndRecord(...)` is the gated execution entry point. It verifies the persisted
proposal-specific approval and authenticated reviewer before invoking either evaluator,
then rechecks the same approval before storing the result. Callers supply separate
baseline and candidate evaluator adapters and descriptors, plus explicit candidate
identities/versions and metric-direction thresholds.

`ShadowEvaluationRunner` executes the baseline and candidate sequentially against the
same immutable `Evaluator.Dataset` instance and revision. Sequential pairing avoids overlap
between potentially stateful adapters. The harness runtime validates one correctly
identified result per case and computes aggregate metrics. Required comparison metrics
must exist on both sides; missing metrics fail the run rather than being treated as
zero or as a pass.

The proposal policy fingerprint supplied to the runner must bind the exact proposed candidate identity/version, evaluator configuration, and metric policy approved by the reviewer; do not reuse a fingerprint across materially different candidate configurations. The persisted report separately retains both model identities/versions and both evaluator configuration fingerprints for audit.

The dataset fingerprint is SHA-256 over the dataset ID/version and ordered case IDs,
inputs, expected outputs, and sorted metadata. Raw dataset contents and case-level
details are not persisted. Two content-digest evidence references are generated from
the baseline and candidate evaluation reports; they include evaluator identity/config,
case IDs, pass flags, and metrics, but exclude raw inputs, expected outputs, and
free-form result details. Keep the exact dataset revision and evaluator artifacts
available in the controlled evaluation environment if reruns are required.

This is offline evaluator orchestration, not production traffic shadowing: the supplied
adapters must be explicitly configured to invoke isolated baseline/candidate instances
and must not route through production user traffic. The runner has no router, deployment,
or promotion integration. The service does not create model instances, resolve providers,
fetch datasets, or execute arbitrary candidate code. The caller owns those trusted
provisioning boundaries and must pass an immutable, approved dataset and isolated
adapters. A PASS remains evidence only and never activates a candidate.
