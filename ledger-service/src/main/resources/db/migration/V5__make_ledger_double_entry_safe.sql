ALTER TABLE ledger_entries
DROP CONSTRAINT IF EXISTS unique_payment_id;

ALTER TABLE ledger_entries
    ALTER COLUMN payment_id SET NOT NULL;

ALTER TABLE ledger_entries
    ADD CONSTRAINT uk_ledger_payment_entry_type
        UNIQUE (payment_id, entry_type);