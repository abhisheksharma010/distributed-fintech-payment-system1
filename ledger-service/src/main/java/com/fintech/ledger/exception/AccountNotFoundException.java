package com.fintech.ledger.exception;

public class AccountNotFoundException
        extends RuntimeException {

    public AccountNotFoundException(
            String currency
    ) {
        super(
                "Account not found for currency "
                        + currency
        );
    }
}