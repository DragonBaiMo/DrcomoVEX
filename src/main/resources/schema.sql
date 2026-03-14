CREATE TABLE IF NOT EXISTS player_variables (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid VARCHAR(36) NOT NULL,
    variable_key VARCHAR(255) NOT NULL,
    value TEXT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    first_modified_at BIGINT NOT NULL,
    UNIQUE(player_uuid, variable_key)
);

CREATE TABLE IF NOT EXISTS server_variables (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    variable_key VARCHAR(255) NOT NULL UNIQUE,
    value TEXT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    first_modified_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS player_variable_change_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    server_id VARCHAR(64) NOT NULL,
    player_uuid VARCHAR(36),
    variable_key VARCHAR(255) NOT NULL,
    value TEXT,
    updated_at BIGINT NOT NULL,
    first_modified_at BIGINT NOT NULL,
    scope VARCHAR(16) NOT NULL,
    op VARCHAR(32) NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS player_variable_change_consumers (
    server_id VARCHAR(64) NOT NULL PRIMARY KEY,
    last_event_id BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_player_variables_uuid ON player_variables(player_uuid);
CREATE INDEX IF NOT EXISTS idx_player_variables_key ON player_variables(variable_key);
CREATE INDEX IF NOT EXISTS idx_server_variables_key ON server_variables(variable_key);
CREATE INDEX IF NOT EXISTS idx_change_events_server ON player_variable_change_events(server_id);
CREATE INDEX IF NOT EXISTS idx_change_events_created ON player_variable_change_events(created_at);
CREATE INDEX IF NOT EXISTS idx_change_events_scope_key ON player_variable_change_events(scope, variable_key);
