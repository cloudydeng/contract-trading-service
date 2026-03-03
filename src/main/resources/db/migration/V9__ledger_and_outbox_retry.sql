CREATE TABLE IF NOT EXISTS account_balance (
    user_id BIGINT NOT NULL,
    asset VARCHAR(16) NOT NULL,
    available NUMERIC(36, 18) NOT NULL,
    frozen NUMERIC(36, 18) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, asset)
);

CREATE TABLE IF NOT EXISTS ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    asset VARCHAR(16) NOT NULL,
    biz_type VARCHAR(32) NOT NULL,
    ref_id VARCHAR(64) NOT NULL,
    delta NUMERIC(36, 18) NOT NULL,
    balance_after NUMERIC(36, 18) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ledger_entries_dedupe
    ON ledger_entries (user_id, biz_type, ref_id, asset);

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS last_error TEXT;

CREATE INDEX IF NOT EXISTS idx_outbox_status_retry
    ON outbox_events (status, retry_count, id);
