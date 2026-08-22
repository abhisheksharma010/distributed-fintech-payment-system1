package com.fintech.payment.exception;

public class SenderIdentityMismatchException
        extends RuntimeException {

    public SenderIdentityMismatchException() {

        super(
                "Authenticated user is not allowed to create a payment for the supplied senderId"
        );
    }
}