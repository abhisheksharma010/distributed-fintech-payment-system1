package com.fintech.payment.exception;

public class AuthenticatedUserRequiredException
        extends RuntimeException {

    public AuthenticatedUserRequiredException(
            String message
    ) {
        super(message);
    }
}