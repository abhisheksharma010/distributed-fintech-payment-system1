CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE payments (

                          id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

                          user_id UUID NOT NULL,

                          amount DECIMAL(12,2) NOT NULL,

                          currency VARCHAR(10) NOT NULL DEFAULT 'USD',

                          status VARCHAR(50) NOT NULL,

                          idempotency_key VARCHAR(255),

                          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                          updated_at TIMESTAMP
);

CREATE UNIQUE INDEX idx_payment_idempotency
    ON payments(idempotency_key);

CREATE INDEX idx_payment_user
    ON payments(user_id);

CREATE INDEX idx_payment_status
    ON payments(status);