package com.fintech.ledger.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record LedgerRecoveryResponse(
        UUID paymentId,
        LedgerRecoveryState state,
        UUID ledgerTransactionId,
        String failureReason,
        LocalDateTime resolvedAt
) {
}