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

CREATE INDEX IF NOT EXISTS idx_player_variables_uuid ON player_variables(player_uuid);
CREATE INDEX IF NOT EXISTS idx_player_variables_key ON player_variables(variable_key);
CREATE INDEX IF NOT EXISTS idx_server_variables_key ON server_variables(variable_key);
