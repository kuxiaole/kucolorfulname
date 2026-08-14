CREATE TABLE IF NOT EXISTS nickname_grants (
    id TEXT PRIMARY KEY,
    player_uuid TEXT NOT NULL,
    style_key TEXT NOT NULL,
    granted_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    granted_by TEXT NULL,
    source TEXT NOT NULL,
    UNIQUE (player_uuid, style_key)
);

CREATE INDEX IF NOT EXISTS idx_nickname_grants_player
    ON nickname_grants (player_uuid);
CREATE INDEX IF NOT EXISTS idx_nickname_grants_expiry
    ON nickname_grants (expires_at);

CREATE TABLE IF NOT EXISTS active_styles (
    player_uuid TEXT PRIMARY KEY,
    style_key TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS grant_audit (
    id TEXT PRIMARY KEY,
    player_uuid TEXT NOT NULL,
    style_key TEXT NOT NULL,
    action TEXT NOT NULL,
    occurred_at INTEGER NOT NULL,
    actor_uuid TEXT NULL,
    source TEXT NOT NULL,
    previous_expires_at INTEGER NULL,
    new_expires_at INTEGER NULL
);

CREATE INDEX IF NOT EXISTS idx_grant_audit_player_time
    ON grant_audit (player_uuid, occurred_at DESC);
