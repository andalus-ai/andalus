-- Immutable history of advisory aggregation evaluations.
CREATE TABLE IF NOT EXISTS andalus_feedback_aggregation_snapshot (
    snapshot_id VARCHAR(36) NOT NULL PRIMARY KEY,
    subject_kind VARCHAR(32) NOT NULL,
    subject_id VARCHAR(255) NOT NULL,
    outcome VARCHAR(64) NOT NULL,
    positive_weight DOUBLE PRECISION NOT NULL,
    negative_weight DOUBLE PRECISION NOT NULL,
    ambiguous_weight DOUBLE PRECISION NOT NULL,
    positive_ratio DOUBLE PRECISION NOT NULL,
    negative_ratio DOUBLE PRECISION NOT NULL,
    distinct_sources INTEGER NOT NULL,
    evidence_references INTEGER NOT NULL,
    trust_policy_fingerprint VARCHAR(64) NOT NULL,
    aggregation_policy_fingerprint VARCHAR(64) NOT NULL,
    corroboration_fingerprint VARCHAR(64) NOT NULL,
    created_at_epoch_millis BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS andalus_feedback_aggregation_snapshot_item (
    snapshot_id VARCHAR(36) NOT NULL,
    item_kind VARCHAR(32) NOT NULL,
    item_order INTEGER NOT NULL,
    item_value VARCHAR(2048) NOT NULL,
    CONSTRAINT pk_feedback_snapshot_item PRIMARY KEY (snapshot_id, item_kind, item_order),
    CONSTRAINT fk_feedback_snapshot_item FOREIGN KEY (snapshot_id)
        REFERENCES andalus_feedback_aggregation_snapshot(snapshot_id)
);
