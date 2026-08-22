ALTER TABLE outbox_events
    ADD COLUMN published_at TIMESTAMP,
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_attempt_at TIMESTAMP,
    ADD COLUMN last_error VARCHAR(1000);

CREATE INDEX idx_outbox_pending_created_at
    ON outbox_events(created_at)
    WHERE published = FALSE;