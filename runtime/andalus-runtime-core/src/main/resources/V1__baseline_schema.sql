-- ─────────────────────────────────────────────────────────────────────────────
-- V1__baseline_schema.sql
-- Aqli Agent System — Baseline PostgreSQL schema
-- Flyway migration: applied automatically at startup via quarkus.flyway.migrate-at-start=true
-- ─────────────────────────────────────────────────────────────────────────────

-- pgvector extension (must be installed in PostgreSQL)
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ── Working memory (ephemeral, per-run) ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS agent_working_memory (
    id          BIGSERIAL    PRIMARY KEY,
    run_id      VARCHAR(128) NOT NULL,
    key         VARCHAR(256) NOT NULL,
    value       JSONB        NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (run_id, key)
);

CREATE INDEX IF NOT EXISTS idx_wm_run ON agent_working_memory (run_id);

-- Auto-clean working memory older than 24 hours (run a scheduled job or pg_cron)
-- CREATE EXTENSION IF NOT EXISTS pg_cron;
-- SELECT cron.schedule('0 * * * *', $$DELETE FROM agent_working_memory WHERE created_at < NOW() - INTERVAL '24 hours'$$);

-- ── Conversation history ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS conversation_history (
    msg_id      UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id  VARCHAR(128) NOT NULL,
    tenant_id   VARCHAR(128) NOT NULL DEFAULT 'community',
    role        VARCHAR(32)  NOT NULL,   -- user | assistant | system | tool
    content     TEXT         NOT NULL,
    metadata    JSONB        NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_conv_session     ON conversation_history (session_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_conv_tenant      ON conversation_history (tenant_id, created_at DESC);

-- ── Long-term memory / facts (pgvector) ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS agent_facts (
    fact_id     UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id   VARCHAR(128) NOT NULL DEFAULT 'community',
    content     TEXT         NOT NULL,
    -- Embedding vector — dimension must match your embedding model
    -- Common dimensions: 768 (MiniLM), 1536 (text-embedding-3-small), 3072 (large)
    embedding   vector(1536),
    tags        TEXT[]       NOT NULL DEFAULT '{}',
    source      VARCHAR(256),
    confidence  FLOAT        NOT NULL DEFAULT 1.0,
    metadata    JSONB        NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ  -- NULL = never expires
);

-- HNSW index for fast approximate nearest-neighbour search
-- Parameters: m=16 (connections per node), ef_construction=64 (build time accuracy)
-- Tune up for higher recall: m=32, ef_construction=128
CREATE INDEX IF NOT EXISTS idx_facts_embedding_hnsw
    ON agent_facts
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS idx_facts_tenant     ON agent_facts (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_facts_tags       ON agent_facts USING GIN (tags);
CREATE INDEX IF NOT EXISTS idx_facts_fts
    ON agent_facts USING GIN (to_tsvector('english', content));

-- ── Agent runs ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS agent_runs (
    run_id        UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    request_id    VARCHAR(128) NOT NULL,
    tenant_id     VARCHAR(128) NOT NULL DEFAULT 'community',
    session_id    VARCHAR(128),
    strategy      VARCHAR(64)  NOT NULL,
    prompt        TEXT         NOT NULL,
    answer        TEXT,
    model_id      VARCHAR(256),
    total_steps   INT          NOT NULL DEFAULT 0,
    successful    BOOLEAN      NOT NULL DEFAULT FALSE,
    error_message TEXT,
    duration_ms   BIGINT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_runs_tenant  ON agent_runs (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_runs_session ON agent_runs (session_id);

-- ── Agent run steps (reasoning trace) ────────────────────────────────────────
CREATE TABLE IF NOT EXISTS agent_run_steps (
    step_id      UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id       UUID         NOT NULL REFERENCES agent_runs(run_id) ON DELETE CASCADE,
    step_number  INT          NOT NULL,
    thought      TEXT,
    action       VARCHAR(256),
    action_input TEXT,
    observation  TEXT,
    skill_id     VARCHAR(256),
    duration_ms  BIGINT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_steps_run ON agent_run_steps (run_id, step_number ASC);

-- ── Skill invocations (audit) ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS skill_invocations (
    invocation_id UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id        UUID         REFERENCES agent_runs(run_id) ON DELETE SET NULL,
    tenant_id     VARCHAR(128) NOT NULL DEFAULT 'community',
    skill_id      VARCHAR(256) NOT NULL,
    status        VARCHAR(32)  NOT NULL,   -- SUCCESS | FAILURE | PARTIAL | SKIPPED
    observation   TEXT,
    error_message TEXT,
    retryable     BOOLEAN      NOT NULL DEFAULT FALSE,
    duration_ms   BIGINT,
    invoked_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_invocations_tenant_skill
    ON skill_invocations (tenant_id, skill_id, invoked_at DESC);

-- ── Model registry ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS model_registry (
    model_id      VARCHAR(256) PRIMARY KEY,
    name          VARCHAR(256) NOT NULL,
    version       VARCHAR(64)  NOT NULL DEFAULT 'latest',
    format        VARCHAR(64)  NOT NULL,   -- gguf | safetensors | onnx | libtorch
    storage_uri   TEXT,
    checksum      VARCHAR(128),
    tenant_id     VARCHAR(128) NOT NULL DEFAULT 'community',
    metadata      JSONB        NOT NULL DEFAULT '{}',
    registered_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_model_registry_tenant
    ON model_registry (tenant_id, format);
CREATE INDEX IF NOT EXISTS idx_model_registry_name
    ON model_registry USING GIN (name gin_trgm_ops);

-- ── MCP server registry ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS mcp_servers (
    server_id   VARCHAR(128) PRIMARY KEY,
    name        VARCHAR(256),
    url         TEXT         NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    headers     JSONB        NOT NULL DEFAULT '{}',
    capabilities JSONB       NOT NULL DEFAULT '{}',
    last_seen   TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ── A2A remote agents ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS a2a_remote_agents (
    agent_id    VARCHAR(128) PRIMARY KEY,
    name        VARCHAR(256),
    url         TEXT         NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    card_json   JSONB,       -- cached AgentCard
    last_seen   TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ── Seed data ─────────────────────────────────────────────────────────────────
INSERT INTO model_registry (model_id, name, version, format, tenant_id)
VALUES ('default', 'Community Default Model', '1.0', 'gguf', 'community')
ON CONFLICT DO NOTHING;
