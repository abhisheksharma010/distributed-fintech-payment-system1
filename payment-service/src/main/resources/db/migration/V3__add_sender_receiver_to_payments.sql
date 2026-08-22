ALTER TABLE payments
    ADD COLUMN sender_id UUID,
    ADD COLUMN receiver_id UUID;

-- Existing rows cannot be assigned a receiver without inventing
-- domain data. Keep legacy rows readable while all new writes
-- provide both transfer parties.
ALTER TABLE payments
    ALTER COLUMN user_id DROP NOT NULL;

CREATE INDEX idx_payment_sender
    ON payments(sender_id);

CREATE INDEX idx_payment_receiver
    ON payments(receiver_id);

ALTER TABLE payments
    ADD CONSTRAINT chk_payment_transfer_parties
    CHECK (
        (sender_id IS NULL AND receiver_id IS NULL)
        OR (
            sender_id IS NOT NULL
            AND receiver_id IS NOT NULL
            AND sender_id <> receiver_id
        )
    );