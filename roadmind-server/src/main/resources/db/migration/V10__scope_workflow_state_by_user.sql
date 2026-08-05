ALTER TABLE workflow_state
    ADD COLUMN user_id BIGINT UNSIGNED NULL AFTER conversation_id;

UPDATE workflow_state AS workflow
JOIN conversation AS conversation
    ON workflow.conversation_id REGEXP '^[0-9]+$'
    AND CAST(workflow.conversation_id AS UNSIGNED) = conversation.id
SET workflow.user_id = conversation.user_id
WHERE workflow.user_id IS NULL;

UPDATE workflow_state AS workflow
JOIN `user` AS owner
    ON owner.username = 'roadmind-demo'
    AND owner.status = 'ACTIVE'
SET workflow.user_id = owner.id
WHERE workflow.user_id IS NULL;

ALTER TABLE workflow_state
    MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL;

ALTER TABLE workflow_state
    ADD CONSTRAINT fk_workflow_state_user
        FOREIGN KEY (user_id) REFERENCES `user` (id);

CREATE INDEX idx_workflow_state_user_conversation_updated
    ON workflow_state (user_id, conversation_id, updated_at);

CREATE INDEX idx_workflow_state_user_id
    ON workflow_state (user_id, workflow_id);
