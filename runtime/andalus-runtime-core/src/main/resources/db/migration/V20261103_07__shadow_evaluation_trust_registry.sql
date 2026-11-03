-- Durable exact-version manifests and signer trust metadata.
-- Public keys remain in the deployment-managed key store; this schema stores references only.
CREATE TABLE IF NOT EXISTS andalus_shadow_trusted_signer (
    signer_id VARCHAR(255) NOT NULL PRIMARY KEY,
    signature_algorithm VARCHAR(64) NOT NULL,
    public_key_reference VARCHAR(2048) NOT NULL,
    signer_status VARCHAR(16) NOT NULL,
    registered_at_epoch_millis BIGINT NOT NULL,
    revoked_at_epoch_millis BIGINT,
    CONSTRAINT ck_shadow_signer_status CHECK (signer_status IN ('ACTIVE', 'REVOKED'))
);

CREATE TABLE IF NOT EXISTS andalus_shadow_dataset_manifest (
    dataset_id VARCHAR(255) NOT NULL,
    dataset_version VARCHAR(128) NOT NULL,
    schema_version VARCHAR(128) NOT NULL,
    case_count INTEGER NOT NULL,
    content_fingerprint VARCHAR(64) NOT NULL,
    source_reference VARCHAR(2048) NOT NULL,
    signer_id VARCHAR(255) NOT NULL,
    signature VARCHAR(4096) NOT NULL,
    registered_at_epoch_millis BIGINT NOT NULL,
    CONSTRAINT pk_shadow_dataset_manifest PRIMARY KEY (dataset_id, dataset_version),
    CONSTRAINT fk_shadow_dataset_signer FOREIGN KEY (signer_id)
        REFERENCES andalus_shadow_trusted_signer(signer_id)
);

CREATE TABLE IF NOT EXISTS andalus_shadow_evaluator_manifest (
    candidate_id VARCHAR(255) NOT NULL,
    candidate_version VARCHAR(128) NOT NULL,
    evaluator_id VARCHAR(255) NOT NULL,
    evaluator_version VARCHAR(128) NOT NULL,
    configuration_fingerprint VARCHAR(64) NOT NULL,
    artifact_id VARCHAR(255) NOT NULL,
    artifact_version VARCHAR(128) NOT NULL,
    artifact_reference VARCHAR(2048) NOT NULL,
    artifact_digest VARCHAR(64) NOT NULL,
    signer_id VARCHAR(255) NOT NULL,
    signature VARCHAR(4096) NOT NULL,
    registered_at_epoch_millis BIGINT NOT NULL,
    CONSTRAINT pk_shadow_evaluator_manifest PRIMARY KEY
        (candidate_id, candidate_version, evaluator_id, evaluator_version, configuration_fingerprint),
    CONSTRAINT fk_shadow_evaluator_signer FOREIGN KEY (signer_id)
        REFERENCES andalus_shadow_trusted_signer(signer_id)
);
