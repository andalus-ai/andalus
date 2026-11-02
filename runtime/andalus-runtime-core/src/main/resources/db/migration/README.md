# Runtime database migrations

Apply the SQL files in this directory with the deployment's chosen migration
runner before enabling the corresponding runtime feature.

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
