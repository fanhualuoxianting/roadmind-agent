ALTER TABLE scheduled_task
    ADD COLUMN authorization_workflow_id VARCHAR(64) NULL AFTER confirmation_item_id,
    ADD COLUMN authorization_step_id VARCHAR(64) NULL AFTER authorization_workflow_id,
    ADD COLUMN authorization_confirmation_id VARCHAR(64) NULL AFTER authorization_step_id,
    ADD COLUMN authorization_plan_version INT NULL AFTER authorization_confirmation_id,
    ADD COLUMN authorization_payload_hash CHAR(64) NULL AFTER authorization_plan_version;

CREATE INDEX idx_scheduled_authorization
    ON scheduled_task (authorization_workflow_id, authorization_step_id, status);

CREATE TABLE domain_event_outbox (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    aggregate_type VARCHAR(48) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    available_at DATETIME(3) NOT NULL,
    locked_by VARCHAR(80) NULL,
    locked_until DATETIME(3) NULL,
    attempt_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    last_error_code VARCHAR(80) NULL,
    delivered_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'CLAIMED', 'DELIVERED', 'FAILED')),
    INDEX idx_outbox_due (status, available_at, id),
    INDEX idx_outbox_aggregate (aggregate_type, aggregate_id, sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
