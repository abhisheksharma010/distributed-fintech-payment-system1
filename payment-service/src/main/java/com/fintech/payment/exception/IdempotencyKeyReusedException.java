package com.fintech.payment.exception;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super(
                "Idempotency key has already been used for a different payment: "
                        + idempotencyKey
        );
    }
}