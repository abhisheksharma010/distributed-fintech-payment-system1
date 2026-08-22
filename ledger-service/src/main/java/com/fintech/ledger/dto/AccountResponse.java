package com.fintech.ledger.dto;

import com.fintech.ledger.entity.AccountStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AccountResponse(
        UUID accountId,
        UUID ownerId,
        BigDecimal balance,
        String currency,
        AccountStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}