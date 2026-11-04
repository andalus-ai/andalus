CREATE TABLE IF NOT EXISTS andalus_agent_runtime_state (
    tenant_id VARCHAR(255) NOT NULL,
    agent_id VARCHAR(255) NOT NULL,
    owner_id VARCHAR(255) NOT NULL,
    generation BIGINT NOT NULL,
    available BOOLEAN NOT NULL,
    priority INTEGER NOT NULL,
    cost_factor DOUBLE PRECISION NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_andalus_agent_runtime_state PRIMARY KEY (tenant_id, agent_id),
    CONSTRAINT ck_andalus_agent_runtime_generation CHECK (generation >= 0),
    CONSTRAINT ck_andalus_agent_runtime_cost CHECK (cost_factor >= 0)
);
CREATE INDEX IF NOT EXISTS idx_andalus_agent_runtime_expiry
    ON andalus_agent_runtime_state (expires_at);
