ALTER TABLE trades
    ADD COLUMN IF NOT EXISTS order_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_trades_order_id
    ON trades (order_id);
