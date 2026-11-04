-- Explicit human approval/rejection of a specific proposed learning-policy change.
CREATE TABLE IF NOT EXISTS andalus_feedback_adaptation_approval (
    approval_id VARCHAR(36) NOT NULL PRIMARY KEY,
    proposal_id VARCHAR(255) NOT NULL,
    subject_kind VARCHAR(32) NOT NULL,
    subject_id VARCHAR(255) NOT NULL,
    source_snapshot_id VARCHAR(36) NOT NULL,
    review_id VARCHAR(36) NOT NULL,
    policy_fingerprint VARCHAR(128) NOT NULL,
    reviewer_id VARCHAR(255) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    rationale VARCHAR(4000) NOT NULL,
    decided_at_epoch_millis BIGINT NOT NULL,
    CONSTRAINT fk_adaptation_approval_snapshot FOREIGN KEY (source_snapshot_id)
        REFERENCES andalus_feedback_aggregation_snapshot(snapshot_id),
    CONSTRAINT fk_adaptation_approval_review FOREIGN KEY (review_id)
        REFERENCES andalus_feedback_trend_review(review_id)
);
