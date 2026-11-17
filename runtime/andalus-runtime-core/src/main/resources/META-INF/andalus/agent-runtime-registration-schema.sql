-- Apply once per runtime database before enabling JdbcAgentRuntimeRegistrationStore.
CREATE TABLE IF NOT EXISTS andalus_agent_runtime_registration (
    tenant_id VARCHAR(200) NOT NULL,
    agent_id VARCHAR(200) NOT NULL,
    instance_id VARCHAR(200) NOT NULL,
    owner_id VARCHAR(200) NOT NULL,
    generation BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_andalus_agent_runtime_registration
        PRIMARY KEY (tenant_id, agent_id, instance_id),
    CONSTRAINT ck_andalus_agent_runtime_generation CHECK (generation > 0),
    CONSTRAINT ck_andalus_agent_runtime_status
        CHECK (status IN ('STARTING', 'AVAILABLE', 'UNAVAILABLE', 'REVOKED'))
);

CREATE INDEX IF NOT EXISTS idx_andalus_agent_runtime_active
    ON andalus_agent_runtime_registration (tenant_id, agent_id, status, expires_at);
