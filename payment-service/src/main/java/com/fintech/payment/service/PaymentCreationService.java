package com.fintech.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.payment.dto.CreatePaymentRequest;
import com.fintech.payment.entity.OutboxEvent;
import com.fintech.payment.entity.Payment;
import com.fintech.payment.entity.PaymentStatus;
import com.fintech.payment.event.PaymentCreatedEvent;
import com.fintech.payment.repository.OutboxRepository;
import com.fintech.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentCreationService {

    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;
    private final PaymentStateMachine paymentStateMachine;
    private final ObjectMapper objectMapper;

    @Transactional
    public Payment createNewPayment(
            CreatePaymentRequest request
    ) {

        LocalDateTime now =
                LocalDateTime.now();

        Payment payment =
                Payment.builder()
                        .id(UUID.randomUUID())
                        .senderId(
                                request.getSenderId()
                        )
                        .receiverId(
                                request.getReceiverId()
                        )
                        .amount(
                                request.getAmount()
                        )
                        .currency(
                                request.getCurrency()
                        )
                        .status(
                                PaymentStatus.CREATED
                        )
                        .idempotencyKey(
                                request.getIdempotencyKey()
                        )
                        .createdAt(now)
                        .updatedAt(now)
                        .recoveryAttemptCount(0)
                        .lastRecoveryAt(null)
                        .build();

        paymentRepository
                .saveAndFlush(payment);

        paymentStateMachine.transition(
                payment,
                PaymentStatus.PROCESSING
        );

        paymentRepository.save(payment);

        PaymentCreatedEvent event =
                new PaymentCreatedEvent(
                        payment.getId(),
                        payment.getSenderId(),
                        payment.getReceiverId(),
                        payment.getAmount(),
                        payment.getCurrency(),
                        payment.getIdempotencyKey(),
                        payment.getStatus(),
                        payment.getCreatedAt()
                );

        OutboxEvent outboxEvent =
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .aggregateType("PAYMENT")
                        .aggregateId(
                                payment.getId()
                        )
                        .eventType(
                                "PAYMENT_CREATED"
                        )
                        .payload(
                                serialize(event)
                        )
                        .createdAt(
                                LocalDateTime.now()
                        )
                        .published(false)
                        .publishedAt(null)
                        .attemptCount(0)
                        .lastAttemptAt(null)
                        .lastError(null)
                        .build();

        outboxRepository.save(
                outboxEvent
        );

        return payment;
    }

    private String serialize(
            PaymentCreatedEvent event
    ) {

        try {

            return objectMapper
                    .writeValueAsString(
                            event
                    );

        } catch (Exception ex) {

            throw new IllegalStateException(
                    "Failed to serialize payment event",
                    ex
            );
        }
    }
}