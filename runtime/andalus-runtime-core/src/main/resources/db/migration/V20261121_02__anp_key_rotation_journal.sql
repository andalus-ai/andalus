-- Durable journal for recoverable ANP DID signing-key rotations.
-- The journal contains identifiers and state only; private key material is never persisted here.
CREATE TABLE IF NOT EXISTS andalus_anp_key_rotations (
    operation_id       VARCHAR(128) PRIMARY KEY,
    did                VARCHAR(512) NOT NULL,
    actor_id           VARCHAR(256) NOT NULL,
    old_key_id         VARCHAR(1024) NOT NULL,
    new_key_id         VARCHAR(1024) NOT NULL,
    expected_revision  BIGINT NOT NULL CHECK (expected_revision > 0),
    target_revision    BIGINT NOT NULL CHECK (target_revision > expected_revision),
    state              VARCHAR(32) NOT NULL CHECK (state IN (
        'PREPARED', 'DID_DOCUMENT_UPDATED', 'NEW_KEY_ACTIVATED',
        'OLD_KEY_REVOKED', 'COMPLETED'
    )),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_andalus_anp_rotation_new_key UNIQUE (did, new_key_id),
    CONSTRAINT ck_andalus_anp_rotation_distinct_keys CHECK (old_key_id <> new_key_id)
);

CREATE INDEX IF NOT EXISTS idx_andalus_anp_key_rotations_recovery
    ON andalus_anp_key_rotations (state, updated_at)
    WHERE state <> 'COMPLETED';
