ALTER TABLE agent_task
    ADD COLUMN user_id BIGINT UNSIGNED NULL AFTER conversation_id,
    ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER user_id,
    ADD COLUMN request_hash CHAR(64) NULL AFTER idempotency_key;

ALTER TABLE agent_task
    ADD UNIQUE KEY uk_agent_task_user_conversation_idempotency (
        user_id, conversation_id, idempotency_key
    ),
    ADD INDEX idx_agent_task_user_updated (user_id, updated_at);
