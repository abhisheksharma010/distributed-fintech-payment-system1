package com.fintech.ledger.exception;

public class AccountAlreadyExistsException
        extends RuntimeException {

    public AccountAlreadyExistsException(
            String currency
    ) {
        super(
                "Account already exists for currency "
                        + currency
        );
    }
}