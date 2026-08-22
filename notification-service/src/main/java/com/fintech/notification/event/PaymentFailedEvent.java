package com.fintech.notification.event;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentFailedEvent {

    private UUID paymentId;
    private UUID senderId;
    private String reason;
    private Instant failedAt;
}