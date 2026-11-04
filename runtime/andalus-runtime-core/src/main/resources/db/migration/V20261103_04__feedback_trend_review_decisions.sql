-- Append-only human dispositions for advisory feedback trend reviews.
CREATE TABLE IF NOT EXISTS andalus_feedback_trend_review (
    review_id VARCHAR(36) NOT NULL PRIMARY KEY,
    subject_kind VARCHAR(32) NOT NULL,
    subject_id VARCHAR(255) NOT NULL,
    baseline_snapshot_id VARCHAR(36),
    current_snapshot_id VARCHAR(36) NOT NULL,
    assessment_decision VARCHAR(32) NOT NULL,
    reviewer_id VARCHAR(255) NOT NULL,
    disposition VARCHAR(32) NOT NULL,
    rationale VARCHAR(4000) NOT NULL,
    reviewed_at_epoch_millis BIGINT NOT NULL,
    CONSTRAINT fk_feedback_review_baseline FOREIGN KEY (baseline_snapshot_id)
        REFERENCES andalus_feedback_aggregation_snapshot(snapshot_id),
    CONSTRAINT fk_feedback_review_current FOREIGN KEY (current_snapshot_id)
        REFERENCES andalus_feedback_aggregation_snapshot(snapshot_id)
);

CREATE TABLE IF NOT EXISTS andalus_feedback_trend_review_reason (
    review_id VARCHAR(36) NOT NULL,
    reason_order INTEGER NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    CONSTRAINT pk_feedback_review_reason PRIMARY KEY (review_id, reason_order),
    CONSTRAINT fk_feedback_review_reason FOREIGN KEY (review_id)
        REFERENCES andalus_feedback_trend_review(review_id)
);
