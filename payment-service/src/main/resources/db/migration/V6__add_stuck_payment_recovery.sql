UPDATE payments
SET updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP)
WHERE updated_at IS NULL;

ALTER TABLE payments
    ALTER COLUMN updated_at
        SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE payments
    ALTER COLUMN updated_at
        SET NOT NULL;

ALTER TABLE payments
    ADD COLUMN recovery_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_recovery_at TIMESTAMP;

CREATE INDEX idx_payments_stuck_recovery
    ON payments(updated_at, last_recovery_at)
    WHERE status = 'PROCESSING';