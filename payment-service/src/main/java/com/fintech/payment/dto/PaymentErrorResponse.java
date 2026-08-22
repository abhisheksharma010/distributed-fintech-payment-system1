package com.fintech.payment.dto;

import java.time.Instant;
import java.util.Map;

public record PaymentErrorResponse(
        String code,
        String message,
        Instant timestamp,
        String path,
        Map<String, String> fieldErrors
) {
}