CREATE TABLE IF NOT EXISTS andalus_anp_signing_keys (
    key_id VARCHAR(512) PRIMARY KEY,
    agent_id VARCHAR(512) NOT NULL,
    algorithm VARCHAR(32) NOT NULL CHECK (algorithm = 'Ed25519'),
    private_key_ciphertext BYTEA NOT NULL,
    public_key_bytes BYTEA NOT NULL,
    revoked_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT andalus_anp_signing_key_material_nonempty
        CHECK (octet_length(private_key_ciphertext) > 0 AND octet_length(public_key_bytes) > 0)
);

CREATE INDEX IF NOT EXISTS idx_andalus_anp_signing_keys_agent_active
    ON andalus_anp_signing_keys (agent_id, created_at DESC)
    WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS andalus_anp_agent_signing_keys (
    agent_id VARCHAR(512) PRIMARY KEY,
    key_id VARCHAR(512) NOT NULL UNIQUE
        REFERENCES andalus_anp_signing_keys(key_id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Tombstones make revocation terminal even when an identifier is revoked before
-- its key material is ever registered.
CREATE TABLE IF NOT EXISTS andalus_anp_revoked_key_ids (
    key_id VARCHAR(512) PRIMARY KEY,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
