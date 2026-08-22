ALTER TABLE accounts
    ALTER COLUMN currency SET NOT NULL,
ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE accounts
    ADD CONSTRAINT uk_accounts_owner_currency
        UNIQUE (owner_id, currency);