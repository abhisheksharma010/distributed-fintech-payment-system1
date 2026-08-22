package com.fintech.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.payment.dto.CreatePaymentRequest;
import com.fintech.payment.entity.OutboxEvent;
import com.fintech.payment.entity.Payment;
import com.fintech.payment.entity.PaymentStatus;
import com.fintech.payment.repository.OutboxRepository;
import com.fintech.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentCreationServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private PaymentStateMachine paymentStateMachine;

    private PaymentCreationService paymentCreationService;

    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .findAndRegisterModules();

    @BeforeEach
    void setUp() {

        paymentCreationService =
                new PaymentCreationService(
                        paymentRepository,
                        outboxRepository,
                        paymentStateMachine,
                        objectMapper
                );
    }

    @Test
    void createsProcessingPaymentAndMatchingOutboxEvent()
            throws Exception {

        CreatePaymentRequest request =
                validRequest();

        doAnswer(invocation -> {

            Payment payment =
                    invocation.getArgument(0);

            PaymentStatus status =
                    invocation.getArgument(1);

            payment.setStatus(status);

            return null;

        }).when(paymentStateMachine)
                .transition(
                        any(Payment.class),
                        any(PaymentStatus.class)
                );

        Payment created =
                paymentCreationService
                        .createNewPayment(request);

        assertEquals(
                request.getSenderId(),
                created.getSenderId()
        );

        assertEquals(
                request.getReceiverId(),
                created.getReceiverId()
        );

        assertEquals(
                0,
                request.getAmount()
                        .compareTo(
                                created.getAmount()
                        )
        );

        assertEquals(
                request.getCurrency(),
                created.getCurrency()
        );

        assertEquals(
                request.getIdempotencyKey(),
                created.getIdempotencyKey()
        );

        assertEquals(
                PaymentStatus.PROCESSING,
                created.getStatus()
        );

        verify(paymentRepository)
                .saveAndFlush(created);

        verify(paymentRepository)
                .save(created);

        ArgumentCaptor<OutboxEvent> captor =
                ArgumentCaptor.forClass(
                        OutboxEvent.class
                );

        verify(outboxRepository)
                .save(captor.capture());

        OutboxEvent outbox =
                captor.getValue();

        assertEquals(
                "PAYMENT",
                outbox.getAggregateType()
        );

        assertEquals(
                created.getId(),
                outbox.getAggregateId()
        );

        assertEquals(
                "PAYMENT_CREATED",
                outbox.getEventType()
        );

        assertFalse(
                outbox.isPublished()
        );

        var payload =
                objectMapper.readTree(
                        outbox.getPayload()
                );

        assertEquals(
                created.getId().toString(),
                payload.get("paymentId").asText()
        );

        assertEquals(
                created.getSenderId().toString(),
                payload.get("senderId").asText()
        );

        assertEquals(
                created.getReceiverId().toString(),
                payload.get("receiverId").asText()
        );

        assertEquals(
                0,
                new BigDecimal(
                        payload.get("amount")
                                .asText()
                ).compareTo(
                        created.getAmount()
                )
        );

        assertEquals(
                "INR",
                payload.get("currency").asText()
        );

        assertEquals(
                created.getIdempotencyKey(),
                payload.get("idempotencyKey")
                        .asText()
        );
    }

    private static CreatePaymentRequest validRequest() {

        CreatePaymentRequest request =
                new CreatePaymentRequest();

        request.setSenderId(
                UUID.randomUUID()
        );

        request.setReceiverId(
                UUID.randomUUID()
        );

        request.setAmount(
                new BigDecimal("2000.00")
        );

        request.setCurrency("INR");

        request.setIdempotencyKey(
                "payment-create-test-"
                        + UUID.randomUUID()
        );

        return request;
    }
}