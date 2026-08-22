package com.fintech.ledger.dto;

import com.fintech.ledger.entity.LedgerEntryType;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID id,
        UUID accountId,
        UUID paymentId,
        LedgerEntryType entryType,
        BigDecimal amount,
        LocalDateTime createdAt
) implements Serializable {
}