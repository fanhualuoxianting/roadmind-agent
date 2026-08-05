ALTER TABLE conversation
    ADD COLUMN context_summary TEXT NULL AFTER context_version,
    ADD COLUMN last_message_at TIMESTAMP(6) NULL AFTER context_summary,
    ADD COLUMN expires_at TIMESTAMP(6) NULL AFTER last_message_at;

ALTER TABLE conversation
    ADD INDEX idx_conversation_expiry (status, expires_at),
    ADD INDEX idx_conversation_user_time (user_id, last_message_at);

ALTER TABLE message
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT;
