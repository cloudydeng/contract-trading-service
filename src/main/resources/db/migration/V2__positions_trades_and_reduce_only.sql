ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS reduce_only BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS positions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    side VARCHAR(8) NOT NULL,
    quantity NUMERIC(36, 18) NOT NULL,
    entry_price NUMERIC(36, 18) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_positions_user_symbol_side
    ON positions (user_id, symbol, side);

CREATE TABLE IF NOT EXISTS trades (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    side VARCHAR(8) NOT NULL,
    quantity NUMERIC(36, 18) NOT NULL,
    price NUMERIC(36, 18) NOT NULL,
    realized_pnl NUMERIC(36, 18) NOT NULL,
    close_trade BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_trades_user_symbol
    ON trades (user_id, symbol);
