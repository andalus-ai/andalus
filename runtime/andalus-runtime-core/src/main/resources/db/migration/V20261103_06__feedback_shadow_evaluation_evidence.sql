-- Append-only evidence for shadow evaluations; no production policy mutation is represented.
CREATE TABLE IF NOT EXISTS andalus_feedback_shadow_evaluation (
    run_id VARCHAR(36) NOT NULL PRIMARY KEY,
    proposal_id VARCHAR(255) NOT NULL,
    subject_kind VARCHAR(32) NOT NULL,
    subject_id VARCHAR(255) NOT NULL,
    source_snapshot_id VARCHAR(36) NOT NULL,
    approval_id VARCHAR(36) NOT NULL,
    policy_fingerprint VARCHAR(128) NOT NULL,
    baseline_evaluator_id VARCHAR(255) NOT NULL,
    baseline_evaluator_version VARCHAR(128) NOT NULL,
    candidate_evaluator_id VARCHAR(255) NOT NULL,
    candidate_evaluator_version VARCHAR(128) NOT NULL,
    dataset_id VARCHAR(255) NOT NULL,
    dataset_version VARCHAR(128) NOT NULL,
    dataset_fingerprint VARCHAR(128) NOT NULL,
    evaluation_status VARCHAR(16) NOT NULL,
    evaluated_at_epoch_millis BIGINT NOT NULL,
    CONSTRAINT fk_shadow_evaluation_snapshot FOREIGN KEY (source_snapshot_id)
        REFERENCES andalus_feedback_aggregation_snapshot(snapshot_id),
    CONSTRAINT fk_shadow_evaluation_approval FOREIGN KEY (approval_id)
        REFERENCES andalus_feedback_adaptation_approval(approval_id)
);

CREATE TABLE IF NOT EXISTS andalus_feedback_shadow_evaluation_metric (
    run_id VARCHAR(36) NOT NULL,
    metric_order INTEGER NOT NULL,
    metric_name VARCHAR(128) NOT NULL,
    baseline_value DOUBLE PRECISION NOT NULL,
    candidate_value DOUBLE PRECISION NOT NULL,
    direction VARCHAR(32) NOT NULL,
    required_improvement DOUBLE PRECISION NOT NULL,
    CONSTRAINT pk_shadow_evaluation_metric PRIMARY KEY (run_id, metric_order),
    CONSTRAINT uq_shadow_evaluation_metric_name UNIQUE (run_id, metric_name),
    CONSTRAINT fk_shadow_metric_run FOREIGN KEY (run_id)
        REFERENCES andalus_feedback_shadow_evaluation(run_id)
);

CREATE TABLE IF NOT EXISTS andalus_feedback_shadow_evaluation_evidence (
    run_id VARCHAR(36) NOT NULL,
    evidence_order INTEGER NOT NULL,
    evidence_reference VARCHAR(2048) NOT NULL,
    CONSTRAINT pk_shadow_evaluation_evidence PRIMARY KEY (run_id, evidence_order),
    CONSTRAINT uq_shadow_evaluation_evidence UNIQUE (run_id, evidence_reference),
    CONSTRAINT fk_shadow_evidence_run FOREIGN KEY (run_id)
        REFERENCES andalus_feedback_shadow_evaluation(run_id)
);
