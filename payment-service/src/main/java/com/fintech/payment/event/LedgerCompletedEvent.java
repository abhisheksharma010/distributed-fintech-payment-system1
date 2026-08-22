package com.fintech.payment.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerCompletedEvent {

    private UUID paymentId;
    private UUID ledgerTransactionId;
    private Instant completedAt;
}