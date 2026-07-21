PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS balances (
    player_uuid TEXT PRIMARY KEY,
    balance INTEGER NOT NULL CHECK(balance >= 0),
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS ledger (
    id TEXT PRIMARY KEY,
    player_uuid TEXT NOT NULL,
    delta INTEGER NOT NULL,
    balance_after INTEGER NOT NULL CHECK(balance_after >= 0),
    reason TEXT NOT NULL,
    source TEXT NOT NULL,
    external_transaction_id TEXT,
    created_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ledger_external
ON ledger(source, external_transaction_id)
WHERE external_transaction_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_ledger_player_time ON ledger(player_uuid, created_at DESC);

CREATE TABLE IF NOT EXISTS purchases (
    transaction_id TEXT PRIMARY KEY,
    player_uuid TEXT NOT NULL,
    category_id TEXT NOT NULL,
    slot_id TEXT NOT NULL,
    price INTEGER NOT NULL CHECK(price >= 0),
    config_revision INTEGER NOT NULL,
    state TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    detail TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS ix_purchases_player_time ON purchases(player_uuid, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_purchases_state ON purchases(state, updated_at);

CREATE TABLE IF NOT EXISTS command_results (
    transaction_id TEXT NOT NULL,
    command_index INTEGER NOT NULL,
    command TEXT NOT NULL,
    success INTEGER NOT NULL,
    result TEXT NOT NULL,
    executed_at INTEGER NOT NULL,
    PRIMARY KEY(transaction_id, command_index),
    FOREIGN KEY(transaction_id) REFERENCES purchases(transaction_id)
);

CREATE TABLE IF NOT EXISTS purchase_limits (
    player_uuid TEXT NOT NULL,
    category_id TEXT NOT NULL,
    slot_id TEXT NOT NULL,
    window_start INTEGER NOT NULL,
    purchase_count INTEGER NOT NULL,
    last_purchase_at INTEGER NOT NULL,
    PRIMARY KEY(player_uuid, category_id, slot_id)
);

CREATE TABLE IF NOT EXISTS audit (
    id TEXT PRIMARY KEY,
    actor_uuid TEXT NOT NULL,
    action TEXT NOT NULL,
    target TEXT NOT NULL,
    detail TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_audit_time ON audit(created_at DESC);

PRAGMA user_version = 1;
