CREATE TABLE IF NOT EXISTS settlement_prices (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(32) NOT NULL,
    index_price NUMERIC(36, 18) NOT NULL,
    mark_price NUMERIC(36, 18) NOT NULL,
    settlement_price NUMERIC(36, 18) NOT NULL,
    method VARCHAR(32) NOT NULL,
    settlement_time TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_settlement_prices_symbol_time
    ON settlement_prices (symbol, settlement_time DESC);
