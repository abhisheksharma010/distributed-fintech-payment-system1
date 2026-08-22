ALTER TABLE ledger_entries
    ADD CONSTRAINT unique_payment_id UNIQUE (payment_id);