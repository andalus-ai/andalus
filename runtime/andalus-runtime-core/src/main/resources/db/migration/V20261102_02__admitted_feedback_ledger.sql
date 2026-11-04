-- Durable, bounded provenance for admitted human feedback.
-- Evidence rows remain normalized in andalus_feedback_evidence; both tables
-- are written in one transaction by JdbcAdmittedFeedbackLedger.
CREATE TABLE IF NOT EXISTS andalus_admitted_feedback (
    signal_id VARCHAR(255) NOT NULL PRIMARY KEY,
    signal_type VARCHAR(64) NOT NULL,
    source_id VARCHAR(255) NOT NULL,
    execution_id VARCHAR(255) NOT NULL,
    polarity VARCHAR(32) NOT NULL,
    turn_id VARCHAR(255),
    goal_id VARCHAR(255),
    task_id VARCHAR(255),
    evaluation_reference VARCHAR(2048),
    occurred_at VARCHAR(64) NOT NULL
);
