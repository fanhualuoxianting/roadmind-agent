INSERT INTO `user` (
    id,
    username,
    display_name,
    password_hash,
    role,
    timezone,
    status,
    created_at,
    updated_at,
    version
) VALUES (
    198000000000000001,
    'roadmind-demo',
    'RoadMind 演示用户',
    NULL,
    'USER',
    'Asia/Shanghai',
    'ACTIVE',
    CURRENT_TIMESTAMP(3),
    CURRENT_TIMESTAMP(3),
    0
)
ON DUPLICATE KEY UPDATE
    username = VALUES(username);

CREATE TABLE user_preference (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    category VARCHAR(32) NOT NULL,
    preference_key VARCHAR(80) NOT NULL,
    value_json JSON NOT NULL,
    sensitivity VARCHAR(24) NOT NULL DEFAULT 'NORMAL',
    expires_at DATETIME(3) NULL,
    deleted_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_preference_user_key UNIQUE (user_id, category, preference_key),
    CONSTRAINT fk_preference_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT chk_preference_category CHECK (category IN (
        'LOCATION', 'CLIMATE', 'CHARGING', 'REMINDER', 'VEHICLE', 'HOME_DEVICE'
    )),
    CONSTRAINT chk_preference_sensitivity CHECK (sensitivity IN ('NORMAL', 'SENSITIVE')),
    INDEX idx_preference_expiry (expires_at),
    INDEX idx_preference_user_active (user_id, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE scheduled_task (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    agent_task_id BIGINT NULL,
    plan_step_id BIGINT NULL,
    confirmation_item_id BIGINT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    execute_at DATETIME(3) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    locked_by VARCHAR(80) NULL,
    locked_until DATETIME(3) NULL,
    last_error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_scheduled_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_scheduled_task_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT chk_scheduled_task_type CHECK (task_type IN (
        'REMINDER', 'HOME_CONTROL', 'START_TRIP'
    )),
    CONSTRAINT chk_scheduled_task_status CHECK (status IN (
        'PENDING', 'CLAIMED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'
    )),
    INDEX idx_scheduled_due (status, execute_at),
    INDEX idx_scheduled_lease (status, locked_until),
    INDEX idx_scheduled_user (user_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
