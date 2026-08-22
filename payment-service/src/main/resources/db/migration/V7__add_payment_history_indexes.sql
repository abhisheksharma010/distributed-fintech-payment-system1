CREATE INDEX IF NOT EXISTS idx_payments_sender_created_at
    ON payments(sender_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_payments_receiver_created_at
    ON payments(receiver_id, created_at DESC);