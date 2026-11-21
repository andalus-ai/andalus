-- Add expiring worker ownership to the ANP rotation recovery journal.
-- Existing incomplete rows remain immediately claimable.
ALTER TABLE andalus_anp_key_rotations
    ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(256),
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS recovery_attempts BIGINT NOT NULL DEFAULT 0
        CHECK (recovery_attempts >= 0);

CREATE INDEX IF NOT EXISTS idx_andalus_anp_rotation_recovery_lease
    ON andalus_anp_key_rotations (lease_until, updated_at)
    WHERE state <> 'COMPLETED';
