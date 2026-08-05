ALTER TABLE workflow_state
    ADD COLUMN user_id BIGINT UNSIGNED NULL AFTER conversation_id;

UPDATE workflow_state AS workflow
JOIN conversation AS conversation
    ON CAST(workflow.conversation_id AS UNSIGNED) = conversation.id
SET workflow.user_id = conversation.user_id
WHERE workflow.user_id IS NULL;

CREATE INDEX idx_workflow_state_user_conversation_updated
    ON workflow_state (user_id, conversation_id, updated_at);

CREATE INDEX idx_workflow_state_user_id
    ON workflow_state (user_id, workflow_id);
