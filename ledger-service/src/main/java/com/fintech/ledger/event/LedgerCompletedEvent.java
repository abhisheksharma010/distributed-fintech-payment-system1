package com.fintech.ledger.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerCompletedEvent {

    private UUID paymentId;
    private UUID ledgerTransactionId;
    private Instant completedAt;
}