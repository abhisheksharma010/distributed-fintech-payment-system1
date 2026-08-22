package com.fintech.ledger.dto;

import java.time.Instant;
import java.util.Map;

public record AccountErrorResponse(
        String code,
        String message,
        Instant timestamp,
        String path,
        Map<String, String> fieldErrors
) {
}