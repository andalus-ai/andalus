-- Append-only, secret-free audit history for ANP signing-key rotations.
CREATE TABLE IF NOT EXISTS andalus_anp_key_rotation_audit (
    event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operation_id VARCHAR(128) NOT NULL REFERENCES andalus_anp_key_rotations(operation_id),
    event_type VARCHAR(48) NOT NULL CHECK (event_type IN (
        'ROTATION_PREPARED', 'STATE_TRANSITIONED', 'RECOVERY_FAILED', 'DEAD_LETTER_REQUEUED'
    )),
    actor_id VARCHAR(256),
    worker_id VARCHAR(256),
    from_state VARCHAR(32),
    to_state VARCHAR(32),
    failure_type VARCHAR(160),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_andalus_anp_rotation_audit_history
    ON andalus_anp_key_rotation_audit (operation_id, event_id DESC);

-- Application roles receive INSERT and SELECT only; UPDATE/DELETE are intentionally not granted here.
