package com.fintech.ledger.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ledger_processing_results")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerProcessingResult {

    @Id
    @Column(name = "payment_id")
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "result_type",
            nullable = false
    )
    private LedgerProcessingResultType resultType;

    @Column(name = "ledger_transaction_id")
    private UUID ledgerTransactionId;

    @Column(
            name = "failure_reason",
            length = 100
    )
    private String failureReason;

    @Column(
            name = "resolved_at",
            nullable = false
    )
    private LocalDateTime resolvedAt;
}