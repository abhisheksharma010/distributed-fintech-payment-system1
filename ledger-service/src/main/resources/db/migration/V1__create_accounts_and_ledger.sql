CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE accounts (

                          id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

                          owner_id UUID NOT NULL,

                          account_type VARCHAR(50) NOT NULL,

                          balance DECIMAL(14,2) NOT NULL DEFAULT 0,

                          created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ledger_entries (

                                id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

                                account_id UUID NOT NULL,

                                payment_id UUID,

                                entry_type VARCHAR(20) NOT NULL,

                                amount DECIMAL(14,2) NOT NULL,

                                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                                CONSTRAINT fk_ledger_account
                                    FOREIGN KEY (account_id) REFERENCES accounts(id)
);

CREATE INDEX idx_ledger_account
    ON ledger_entries(account_id);

CREATE INDEX idx_ledger_payment
    ON ledger_entries(payment_id);