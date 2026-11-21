-- Trusted DID documents are written only through the explicit local trust boundary.
-- Revision and document are persisted in one row so they cannot diverge.
CREATE TABLE IF NOT EXISTS andalus_trusted_did_documents (
    did             VARCHAR(512) PRIMARY KEY,
    revision        BIGINT NOT NULL CHECK (revision > 0),
    document_json   JSONB NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_andalus_trusted_did_documents_updated_at
    ON andalus_trusted_did_documents (updated_at);
