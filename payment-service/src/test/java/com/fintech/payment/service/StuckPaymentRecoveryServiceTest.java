package com.fintech.payment.service;

import com.fintech.payment.config.PaymentRecoveryProperties;
import com.fintech.payment.dto.LedgerRecoveryResponse;
import com.fintech.payment.dto.LedgerRecoveryState;
import com.fintech.payment.entity.OutboxEvent;
import com.fintech.payment.entity.Payment;
import com.fintech.payment.entity.PaymentStatus;
import com.fintech.payment.event.LedgerCompletedEvent;
import com.fintech.payment.event.LedgerFailedEvent;
import com.fintech.payment.repository.OutboxRepository;
import com.fintech.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StuckPaymentRecoveryServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private PaymentService paymentService;

    @Mock
    private LedgerRecoveryClient ledgerRecoveryClient;

    @Mock
    private StuckPaymentRecoveryMetrics metrics;

    private PaymentRecoveryProperties properties;

    private StuckPaymentRecoveryService recoveryService;

    @BeforeEach
    void setUp() {

        properties =
                new PaymentRecoveryProperties();

        properties.setProcessingTimeoutMs(
                300_000
        );

        properties.setRetryIntervalMs(
                60_000
        );

        properties.setBatchSize(20);
        properties.setMaxAttempts(3);

        recoveryService =
                new StuckPaymentRecoveryService(
                        paymentRepository,
                        outboxRepository,
                        paymentService,
                        ledgerRecoveryClient,
                        properties,
                        metrics
                );
    }

    @Test
    void noStuckPaymentsDoesNothing() {

        when(
                paymentRepository
                        .findStuckPaymentsForRecovery(
                                any(),
                                any(),
                                eq(20)
                        )
        ).thenReturn(
                List.of()
        );

        int count =
                recoveryService
                        .recoverStuckPayments();

        assertEquals(
                0,
                count
        );

        verifyNoInteractions(
                ledgerRecoveryClient,
                paymentService,
                outboxRepository
        );
    }

    @Test
    void ledgerProcessedPaymentRecoversToCompleted() {

        Payment payment =
                stuckPayment();

        UUID ledgerTransactionId =
                UUID.randomUUID();

        when(
                paymentRepository
                        .findStuckPaymentsForRecovery(
                                any(),
                                any(),
                                eq(20)
                        )
        ).thenReturn(
                List.of(payment)
        );

        when(
                ledgerRecoveryClient.lookup(
                        payment.getId()
                )
        ).thenReturn(
                new LedgerRecoveryResponse(
                        payment.getId(),
                        LedgerRecoveryState.PROCESSED,
                        ledgerTransactionId,
                        null,
                        LocalDateTime.now()
                )
        );

        recoveryService
                .recoverStuckPayments();

        ArgumentCaptor<LedgerCompletedEvent>
                captor =
                ArgumentCaptor.forClass(
                        LedgerCompletedEvent.class
                );

        verify(paymentService)
                .handleLedgerCompleted(
                        captor.capture()
                );

        assertEquals(
                payment.getId(),
                captor.getValue()
                        .getPaymentId()
        );

        assertEquals(
                ledgerTransactionId,
                captor.getValue()
                        .getLedgerTransactionId()
        );

        assertEquals(
                1,
                payment.getRecoveryAttemptCount()
        );

        assertNotNull(
                payment.getLastRecoveryAt()
        );

        verify(metrics)
                .recordRecovered();

        verifyNoInteractions(
                outboxRepository
        );
    }

    @Test
    void ledgerBusinessFailureRecoversToFailedWithOriginalReason() {

        Payment payment =
                stuckPayment();

        when(
                paymentRepository
                        .findStuckPaymentsForRecovery(
                                any(),
                                any(),
                                eq(20)
                        )
        ).thenReturn(
                List.of(payment)
        );

        when(
                ledgerRecoveryClient.lookup(
                        payment.getId()
                )
        ).thenReturn(
                new LedgerRecoveryResponse(
                        payment.getId(),
                        LedgerRecoveryState.FAILED,
                        null,
                        "INSUFFICIENT_BALANCE",
                        LocalDateTime.now()
                )
        );

        recoveryService
                .recoverStuckPayments();

        ArgumentCaptor<LedgerFailedEvent>
                captor =
                ArgumentCaptor.forClass(
                        LedgerFailedEvent.class
                );

        verify(paymentService)
                .handleLedgerFailed(
                        captor.capture()
                );

        assertEquals(
                "INSUFFICIENT_BALANCE",
                captor.getValue()
                        .getReason()
        );

        assertEquals(
                payment.getId(),
                captor.getValue()
                        .getPaymentId()
        );

        verify(metrics)
                .recordRecovered();

        verifyNoInteractions(
                outboxRepository
        );
    }

    @Test
    void missingLedgerRecordReenablesExistingOutboxEvent() {

        Payment payment =
                stuckPayment();

        OutboxEvent outbox =
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .aggregateId(
                                payment.getId()
                        )
                        .eventType(
                                "PAYMENT_CREATED"
                        )
                        .published(true)
                        .publishedAt(
                                LocalDateTime.now()
                        )
                        .lastError(
                                "old error"
                        )
                        .build();

        when(
                paymentRepository
                        .findStuckPaymentsForRecovery(
                                any(),
                                any(),
                                eq(20)
                        )
        ).thenReturn(
                List.of(payment)
        );

        when(
                ledgerRecoveryClient.lookup(
                        payment.getId()
                )
        ).thenReturn(
                new LedgerRecoveryResponse(
                        payment.getId(),
                        LedgerRecoveryState.NOT_FOUND,
                        null,
                        null,
                        null
                )
        );

        when(
                outboxRepository
                        .findFirstByAggregateIdAndEventTypeOrderByCreatedAtAsc(
                                payment.getId(),
                                "PAYMENT_CREATED"
                        )
        ).thenReturn(
                Optional.of(outbox)
        );

        recoveryService
                .recoverStuckPayments();

        assertFalse(
                outbox.isPublished()
        );

        assertNull(
                outbox.getPublishedAt()
        );

        assertNull(
                outbox.getLastError()
        );

        assertEquals(
                1,
                payment.getRecoveryAttemptCount()
        );

        verify(outboxRepository)
                .save(outbox);

        verify(metrics)
                .recordRetry();

        verifyNoInteractions(
                paymentService
        );
    }

    @Test
    void recoveryExhaustionDoesNotDeclareFinancialFailureWhenLedgerIsNotFound() {

        Payment payment =
                stuckPayment();

        payment.setRecoveryAttemptCount(2);

        when(
                paymentRepository
                        .findStuckPaymentsForRecovery(
                                any(),
                                any(),
                                eq(20)
                        )
        ).thenReturn(
                List.of(payment)
        );

        when(
                ledgerRecoveryClient.lookup(
                        payment.getId()
                )
        ).thenReturn(
                new LedgerRecoveryResponse(
                        payment.getId(),
                        LedgerRecoveryState.NOT_FOUND,
                        null,
                        null,
                        null
                )
        );

        recoveryService
                .recoverStuckPayments();

        assertEquals(
                PaymentStatus.PROCESSING,
                payment.getStatus()
        );

        assertEquals(
                3,
                payment.getRecoveryAttemptCount()
        );

        verify(metrics)
                .recordFailed();

        verifyNoInteractions(
                paymentService
        );

        verifyNoInteractions(
                outboxRepository
        );
    }

    @Test
    void ledgerUnavailableDoesNotFailPaymentOrConsumeRetryBudget() {

        Payment payment =
                stuckPayment();

        payment.setRecoveryAttemptCount(2);

        when(
                paymentRepository
                        .findStuckPaymentsForRecovery(
                                any(),
                                any(),
                                eq(20)
                        )
        ).thenReturn(
                List.of(payment)
        );

        when(
                ledgerRecoveryClient.lookup(
                        payment.getId()
                )
        ).thenThrow(
                new LedgerRecoveryClient
                        .LedgerRecoveryUnavailableException(
                        payment.getId(),
                        new RuntimeException(
                                "ledger unavailable"
                        )
                )
        );

        recoveryService
                .recoverStuckPayments();

        assertEquals(
                2,
                payment.getRecoveryAttemptCount()
        );

        assertNull(
                payment.getLastRecoveryAt()
        );

        verify(
                paymentRepository,
                never()
        ).save(payment);

        verifyNoInteractions(
                paymentService,
                outboxRepository
        );

        verify(
                metrics,
                never()
        ).recordFailed();
    }

    @Test
    void processedLedgerResultNeverRetriesOutbox() {

        Payment payment =
                stuckPayment();

        when(
                paymentRepository
                        .findStuckPaymentsForRecovery(
                                any(),
                                any(),
                                eq(20)
                        )
        ).thenReturn(
                List.of(payment)
        );

        when(
                ledgerRecoveryClient.lookup(
                        payment.getId()
                )
        ).thenReturn(
                new LedgerRecoveryResponse(
                        payment.getId(),
                        LedgerRecoveryState.PROCESSED,
                        UUID.randomUUID(),
                        null,
                        LocalDateTime.now()
                )
        );

        recoveryService
                .recoverStuckPayments();

        verifyNoInteractions(
                outboxRepository
        );

        verify(paymentService)
                .handleLedgerCompleted(
                        any(
                                LedgerCompletedEvent.class
                        )
                );

        verify(
                paymentService,
                never()
        ).handleLedgerFailed(
                any(
                        LedgerFailedEvent.class
                )
        );
    }

    private static Payment stuckPayment() {

        LocalDateTime now =
                LocalDateTime.now();

        return Payment.builder()
                .id(UUID.randomUUID())
                .senderId(UUID.randomUUID())
                .receiverId(UUID.randomUUID())
                .status(
                        PaymentStatus.PROCESSING
                )
                .createdAt(
                        now.minusMinutes(10)
                )
                .updatedAt(
                        now.minusMinutes(10)
                )
                .recoveryAttemptCount(0)
                .build();
    }
}