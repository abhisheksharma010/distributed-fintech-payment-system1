CREATE TABLE ledger_processing_results (

                                           payment_id UUID PRIMARY KEY,

                                           result_type VARCHAR(30) NOT NULL,

                                           ledger_transaction_id UUID,

                                           failure_reason VARCHAR(100),

                                           resolved_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_ledger_processing_result_type
    ON ledger_processing_results(result_type);