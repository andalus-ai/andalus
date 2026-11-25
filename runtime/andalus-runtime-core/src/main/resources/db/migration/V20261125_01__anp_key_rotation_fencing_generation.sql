-- Monotonically increasing lease generation fences workers that resume after a lease is reclaimed.
ALTER TABLE andalus_anp_key_rotations
    ADD COLUMN IF NOT EXISTS lease_generation BIGINT NOT NULL DEFAULT 0
        CHECK (lease_generation >= 0);
