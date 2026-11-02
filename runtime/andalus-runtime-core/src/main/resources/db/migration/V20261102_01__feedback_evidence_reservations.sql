-- Andalus Runtime: durable feedback evidence reservations.
-- Apply through the deployment's database migration mechanism before enabling
-- feedback admission. Unique constraints are the cross-process concurrency guard.
CREATE TABLE IF NOT EXISTS andalus_feedback_evidence (
    evidence_reference VARCHAR(2048) NOT NULL PRIMARY KEY,
    signal_id VARCHAR(255) NOT NULL,
    reference_order INTEGER NOT NULL,
    CONSTRAINT uq_feedback_signal_order UNIQUE (signal_id, reference_order)
);
