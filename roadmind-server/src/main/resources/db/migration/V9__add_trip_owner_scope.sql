ALTER TABLE trip
    ADD COLUMN owner_user_id BIGINT UNSIGNED NULL AFTER public_id,
    ADD INDEX idx_trip_owner_updated (owner_user_id, updated_at);
