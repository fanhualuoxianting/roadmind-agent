CREATE TABLE conversation (
    id BIGINT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(160) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    context_version INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE message (
    id BIGINT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    role VARCHAR(24) NOT NULL,
    content TEXT NOT NULL,
    context_version INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id)
);

CREATE TABLE agent_task (
    id BIGINT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    goal TEXT NOT NULL,
    status VARCHAR(40) NOT NULL,
    context_json JSON NOT NULL,
    context_version INT NOT NULL,
    plan_version INT NOT NULL DEFAULT 0,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_task_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id)
);

CREATE TABLE plan_step (
    id BIGINT PRIMARY KEY,
    agent_task_id BIGINT NOT NULL,
    plan_version INT NOT NULL,
    step_key VARCHAR(64) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    risk_level VARCHAR(24) NOT NULL,
    status VARCHAR(40) NOT NULL,
    arguments_json JSON NOT NULL,
    dependencies_json JSON NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    verification_json JSON NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_plan_step_version (agent_task_id, plan_version, step_key),
    UNIQUE KEY uk_plan_step_idempotency (idempotency_key),
    CONSTRAINT fk_step_task FOREIGN KEY (agent_task_id) REFERENCES agent_task(id)
);

CREATE TABLE tool_call (
    id BIGINT PRIMARY KEY,
    plan_step_id BIGINT NOT NULL,
    attempt INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    result_json JSON NULL,
    error_code VARCHAR(80) NULL,
    started_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_call_step FOREIGN KEY (plan_step_id) REFERENCES plan_step(id)
);

CREATE TABLE confirmation (
    id BIGINT PRIMARY KEY,
    agent_task_id BIGINT NOT NULL,
    plan_version INT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    decided_at TIMESTAMP(6) NULL,
    decided_by BIGINT UNSIGNED NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_confirmation_payload (agent_task_id, plan_version, payload_hash),
    CONSTRAINT fk_confirmation_task FOREIGN KEY (agent_task_id) REFERENCES agent_task(id)
);

CREATE TABLE confirmation_item (
    id BIGINT PRIMARY KEY,
    confirmation_id BIGINT NOT NULL,
    plan_step_id BIGINT NOT NULL,
    decision VARCHAR(24) NULL,
    UNIQUE KEY uk_confirmation_item (confirmation_id, plan_step_id),
    CONSTRAINT fk_item_confirmation FOREIGN KEY (confirmation_id) REFERENCES confirmation(id),
    CONSTRAINT fk_item_step FOREIGN KEY (plan_step_id) REFERENCES plan_step(id)
);

CREATE TABLE home_device (
    id BIGINT PRIMARY KEY,
    owner_user_id BIGINT UNSIGNED NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    device_type VARCHAR(48) NOT NULL,
    state_json JSON NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE audit_event (
    id BIGINT PRIMARY KEY,
    agent_task_id BIGINT NULL,
    event_type VARCHAR(80) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    detail_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    KEY idx_audit_task_time (agent_task_id, created_at)
);

CREATE INDEX idx_task_conversation_updated ON agent_task(conversation_id, updated_at);
CREATE INDEX idx_step_task_status ON plan_step(agent_task_id, status);
CREATE INDEX idx_confirmation_status_expiry ON confirmation(status, expires_at);
