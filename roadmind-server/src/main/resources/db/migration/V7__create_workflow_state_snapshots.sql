CREATE TABLE workflow_state (
    workflow_id VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL,
    state_json JSON NOT NULL,
    expires_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    INDEX idx_workflow_state_conversation_updated (conversation_id, updated_at),
    INDEX idx_workflow_state_expiry (status, expires_at)
);
