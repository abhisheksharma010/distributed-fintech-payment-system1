package com.fintech.payment.exception;

import java.util.UUID;

public class PaymentAccessDeniedException
        extends RuntimeException {

    public PaymentAccessDeniedException(
            UUID paymentId
    ) {

        super(
                "Authenticated user is not allowed to access payment "
                        + paymentId
        );
    }
}