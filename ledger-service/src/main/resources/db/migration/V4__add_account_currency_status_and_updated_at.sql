ALTER TABLE accounts
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_balance_non_negative
        CHECK (balance >= 0),
    ADD CONSTRAINT chk_accounts_status
        CHECK (status IN ('ACTIVE', 'INACTIVE'));

CREATE INDEX idx_accounts_owner_id
    ON accounts(owner_id);