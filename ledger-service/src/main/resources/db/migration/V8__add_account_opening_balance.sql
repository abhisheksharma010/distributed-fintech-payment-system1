ALTER TABLE accounts
    ADD COLUMN opening_balance DECIMAL(14,2);

UPDATE accounts a
SET opening_balance =
        a.balance
            -
        COALESCE(
                (
                    SELECT SUM(
                                   CASE
                                       WHEN l.entry_type = 'CREDIT'
                                           THEN l.amount
                                       WHEN l.entry_type = 'DEBIT'
                                           THEN -l.amount
                                       ELSE 0
                                       END
                           )
                    FROM ledger_entries l
                    WHERE l.account_id = a.id
                ),
                0
        );

ALTER TABLE accounts
    ALTER COLUMN opening_balance
        SET DEFAULT 0;

ALTER TABLE accounts
    ALTER COLUMN opening_balance
        SET NOT NULL;