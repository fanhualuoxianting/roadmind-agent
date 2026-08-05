CREATE TABLE `user` (
    id BIGINT UNSIGNED NOT NULL,
    username VARCHAR(64) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(255) NULL,
    role VARCHAR(32) NOT NULL,
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    status VARCHAR(24) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_username UNIQUE (username),
    CONSTRAINT chk_user_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT chk_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE vehicle (
    id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    simulator_vehicle_key VARCHAR(80) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    gateway_type VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_vehicle_simulator_key UNIQUE (simulator_vehicle_key),
    CONSTRAINT fk_vehicle_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT chk_vehicle_gateway CHECK (gateway_type = 'SIMULATOR'),
    CONSTRAINT chk_vehicle_status CHECK (status IN ('ACTIVE', 'OFFLINE', 'DISABLED')),
    INDEX idx_vehicle_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE vehicle_state (
    id BIGINT UNSIGNED NOT NULL,
    vehicle_id BIGINT UNSIGNED NOT NULL,
    simulator_version BIGINT UNSIGNED NOT NULL,
    battery_percent DECIMAL(5,2) NOT NULL,
    estimated_range_km DECIMAL(8,2) NOT NULL,
    cabin_temperature DECIMAL(5,2) NOT NULL,
    door_locked TINYINT(1) NOT NULL,
    charging TINYINT(1) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    latitude DECIMAL(10,7) NOT NULL,
    tire_pressure_json JSON NOT NULL,
    snapshot_reason VARCHAR(32) NOT NULL,
    observed_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_vehicle_state_version UNIQUE (vehicle_id, simulator_version),
    CONSTRAINT fk_vehicle_state_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle (id),
    CONSTRAINT chk_vehicle_battery CHECK (battery_percent >= 0 AND battery_percent <= 100),
    CONSTRAINT chk_vehicle_range CHECK (estimated_range_km >= 0),
    INDEX idx_vehicle_state_time (vehicle_id, observed_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
