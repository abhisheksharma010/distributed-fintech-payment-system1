package com.fintech.ledger.service;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountReconciliationResult(
        UUID accountId,
        BigDecimal openingBalance,
        BigDecimal totalCredits,
        BigDecimal totalDebits,
        BigDecimal expectedBalance,
        BigDecimal actualBalance,
        BigDecimal difference,
        ReconciliationStatus status
) {
}