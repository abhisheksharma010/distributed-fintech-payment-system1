package com.fintech.identity.dto;

import java.time.Instant;
import java.util.Map;

public record IdentityErrorResponse(
        String code,
        String message,
        Instant timestamp,
        String path,
        Map<String, String> fieldErrors
) {
}