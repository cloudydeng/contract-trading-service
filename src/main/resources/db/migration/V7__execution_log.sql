CREATE TABLE IF NOT EXISTS execution_log (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(32) NOT NULL,
    sequence_id BIGINT NOT NULL,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    event_type VARCHAR(32) NOT NULL,
    payload TEXT NOT NULL,
    applied BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_execution_log_symbol_seq
    ON execution_log (symbol, sequence_id);

CREATE INDEX IF NOT EXISTS idx_execution_log_symbol_id
    ON execution_log (symbol, id);
