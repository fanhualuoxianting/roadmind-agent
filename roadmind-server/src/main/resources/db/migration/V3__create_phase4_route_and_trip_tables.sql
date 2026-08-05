CREATE TABLE route_plan (
    id BIGINT PRIMARY KEY,
    public_id VARCHAR(64) NOT NULL,
    route_version BIGINT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    source_mode VARCHAR(16) NOT NULL,
    coordinate_system VARCHAR(16) NOT NULL,
    origin_name VARCHAR(120) NOT NULL,
    destination_name VARCHAR(120) NOT NULL,
    distance_meters DECIMAL(12,2) NOT NULL,
    duration_seconds BIGINT NOT NULL,
    route_hash CHAR(64) NOT NULL,
    normalized_route JSON NOT NULL,
    fetched_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_route_plan_version UNIQUE (public_id, route_version)
);

CREATE TABLE trip (
    id BIGINT PRIMARY KEY,
    public_id VARCHAR(64) NOT NULL UNIQUE,
    vehicle_public_id VARCHAR(64) NOT NULL,
    current_route_plan_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    simulation_speed INT NOT NULL DEFAULT 1,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    latest_snapshot JSON NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_trip_route FOREIGN KEY (current_route_plan_id) REFERENCES route_plan(id),
    INDEX idx_trip_status_updated (status, updated_at)
);

CREATE TABLE trip_event (
    id BIGINT PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    event_data JSON NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_trip_event_trip FOREIGN KEY (trip_id) REFERENCES trip(id),
    CONSTRAINT uk_trip_event_sequence UNIQUE (trip_id, sequence),
    INDEX idx_trip_event_occurred (trip_id, occurred_at)
);
