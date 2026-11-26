-- Durable retry scheduling and operator-visible dead-letter state for ANP rotations.
ALTER TABLE andalus_anp_key_rotations
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_failure_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_failure_type VARCHAR(160),
    ADD COLUMN IF NOT EXISTS dead_lettered_at TIMESTAMPTZ;

-- Existing incomplete operations remain immediately eligible for recovery.
CREATE INDEX IF NOT EXISTS idx_andalus_anp_rotation_recovery_due
    ON andalus_anp_key_rotations (next_attempt_at, updated_at)
    WHERE state <> 'COMPLETED' AND dead_lettered_at IS NULL;
