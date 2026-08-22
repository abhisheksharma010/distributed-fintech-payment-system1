package com.fintech.ledger.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCreatedEvent {

    private UUID paymentId;
    private UUID senderId;
    private UUID receiverId;
    private BigDecimal amount;
    private String currency;
    private String idempotencyKey;
    private String status;
    private LocalDateTime createdAt;
}