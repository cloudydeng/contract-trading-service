CREATE TABLE IF NOT EXISTS funding_rates (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(32) NOT NULL,
    index_price NUMERIC(36, 18) NOT NULL,
    mark_price NUMERIC(36, 18) NOT NULL,
    rate NUMERIC(36, 18) NOT NULL,
    funding_time TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_funding_rates_symbol_time
    ON funding_rates (symbol, funding_time);

CREATE TABLE IF NOT EXISTS funding_settlements (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    side VARCHAR(8) NOT NULL,
    quantity NUMERIC(36, 18) NOT NULL,
    rate NUMERIC(36, 18) NOT NULL,
    amount NUMERIC(36, 18) NOT NULL,
    funding_time TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_funding_settlements_dedupe
    ON funding_settlements (user_id, symbol, funding_time);

CREATE INDEX IF NOT EXISTS idx_funding_settlements_symbol_time
    ON funding_settlements (symbol, funding_time DESC);
