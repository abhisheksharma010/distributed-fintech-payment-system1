package com.fintech.payment.service;

import com.fintech.payment.entity.Payment;
import com.fintech.payment.entity.PaymentStatus;
import com.fintech.payment.event.LedgerCompletedEvent;
import com.fintech.payment.event.LedgerFailedEvent;
import com.fintech.payment.event.PaymentCompletedEvent;
import com.fintech.payment.event.PaymentFailedEvent;
import com.fintech.payment.exception.PaymentNotFoundException;
import com.fintech.payment.producer.PaymentEventProducer;
import com.fintech.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentSagaServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentCreationService
            paymentCreationService;

    @Mock
    private PaymentStateMachine
            paymentStateMachine;

    @Mock
    private PaymentEventProducer
            paymentEventProducer;

    @Mock
    private PaymentSagaMetrics
            paymentSagaMetrics;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {

        paymentService =
                new PaymentService(
                        paymentRepository,
                        paymentCreationService,
                        paymentStateMachine,
                        paymentEventProducer
                );

        paymentService
                .setPaymentSagaMetrics(
                        paymentSagaMetrics
                );
    }

    @Test
    void processingPaymentBecomesCompleted() {

        UUID paymentId =
                UUID.randomUUID();

        Payment payment =
                payment(
                        paymentId,
                        PaymentStatus.PROCESSING
                );

        when(
                paymentRepository
                        .findByIdForUpdate(
                                paymentId
                        )
        ).thenReturn(
                Optional.of(payment)
        );

        doAnswer(invocation -> {

            payment.setStatus(
                    invocation.getArgument(1)
            );

            return null;

        }).when(paymentStateMachine)
                .transition(
                        payment,
                        PaymentStatus.COMPLETED
                );

        paymentService
                .handleLedgerCompleted(
                        LedgerCompletedEvent.builder()
                                .paymentId(
                                        paymentId
                                )
                                .ledgerTransactionId(
                                        UUID.randomUUID()
                                )
                                .completedAt(
                                        Instant.now()
                                )
                                .build()
                );

        assertEquals(
                PaymentStatus.COMPLETED,
                payment.getStatus()
        );

        assertNull(
                payment.getFailureReason()
        );

        verify(paymentRepository)
                .save(payment);

        verify(paymentSagaMetrics)
                .recordCompleted();

        verify(paymentEventProducer)
                .sendPaymentCompleted(
                        argThat(
                                emitted ->
                                        paymentId.equals(
                                                emitted.getPaymentId()
                                        )
                                                &&
                                                payment.getSenderId()
                                                        .equals(
                                                                emitted.getSenderId()
                                                        )
                                                &&
                                                emitted.getCompletedAt()
                                                        != null
                        )
                );;
    }

    @Test
    void processingPaymentBecomesFailedAndStoresReason() {

        UUID paymentId =
                UUID.randomUUID();

        Payment payment =
                payment(
                        paymentId,
                        PaymentStatus.PROCESSING
                );

        when(
                paymentRepository
                        .findByIdForUpdate(
                                paymentId
                        )
        ).thenReturn(
                Optional.of(payment)
        );

        doAnswer(invocation -> {

            payment.setStatus(
                    invocation.getArgument(1)
            );

            return null;

        }).when(paymentStateMachine)
                .transition(
                        payment,
                        PaymentStatus.FAILED
                );

        paymentService
                .handleLedgerFailed(
                        LedgerFailedEvent.builder()
                                .paymentId(
                                        paymentId
                                )
                                .reason(
                                        "INSUFFICIENT_BALANCE"
                                )
                                .failedAt(
                                        Instant.now()
                                )
                                .build()
                );

        assertEquals(
                PaymentStatus.FAILED,
                payment.getStatus()
        );

        assertEquals(
                "INSUFFICIENT_BALANCE",
                payment.getFailureReason()
        );

        verify(paymentRepository)
                .save(payment);

        verify(paymentSagaMetrics)
                .recordFailed();

        verify(paymentEventProducer)
                .sendPaymentFailed(
                        argThat(
                                emitted ->
                                        paymentId.equals(
                                                emitted.getPaymentId()
                                        )
                                                &&
                                                payment.getSenderId()
                                                        .equals(
                                                                emitted.getSenderId()
                                                        )
                                                &&
                                                "INSUFFICIENT_BALANCE"
                                                        .equals(
                                                                emitted.getReason()
                                                        )
                                                &&
                                                emitted.getFailedAt()
                                                        != null
                        )
                );
    }

    @Test
    void duplicateCompletionIsIgnored() {

        UUID paymentId =
                UUID.randomUUID();

        Payment payment =
                payment(
                        paymentId,
                        PaymentStatus.COMPLETED
                );

        when(
                paymentRepository
                        .findByIdForUpdate(
                                paymentId
                        )
        ).thenReturn(
                Optional.of(payment)
        );

        paymentService
                .handleLedgerCompleted(
                        LedgerCompletedEvent.builder()
                                .paymentId(
                                        paymentId
                                )
                                .build()
                );

        verify(
                paymentStateMachine,
                never()
        ).transition(
                any(),
                any()
        );

        verify(
                paymentRepository,
                never()
        ).save(any());

        verify(
                paymentEventProducer,
                never()
        ).sendPaymentCompleted(
                any()
        );
    }

    @Test
    void duplicateFailureIsIgnored() {

        UUID paymentId =
                UUID.randomUUID();

        Payment payment =
                payment(
                        paymentId,
                        PaymentStatus.FAILED
                );

        payment.setFailureReason(
                "INSUFFICIENT_BALANCE"
        );

        when(
                paymentRepository
                        .findByIdForUpdate(
                                paymentId
                        )
        ).thenReturn(
                Optional.of(payment)
        );

        paymentService
                .handleLedgerFailed(
                        LedgerFailedEvent.builder()
                                .paymentId(
                                        paymentId
                                )
                                .reason(
                                        "INSUFFICIENT_BALANCE"
                                )
                                .build()
                );

        verify(
                paymentStateMachine,
                never()
        ).transition(
                any(),
                any()
        );

        verify(
                paymentRepository,
                never()
        ).save(any());

        verify(
                paymentEventProducer,
                never()
        ).sendPaymentFailed(
                any()
        );
    }

    @Test
    void completedPaymentCannotBecomeFailed() {

        UUID paymentId =
                UUID.randomUUID();

        Payment payment =
                payment(
                        paymentId,
                        PaymentStatus.COMPLETED
                );

        when(
                paymentRepository
                        .findByIdForUpdate(
                                paymentId
                        )
        ).thenReturn(
                Optional.of(payment)
        );

        paymentService
                .handleLedgerFailed(
                        LedgerFailedEvent.builder()
                                .paymentId(
                                        paymentId
                                )
                                .reason(
                                        "INSUFFICIENT_BALANCE"
                                )
                                .failedAt(
                                        Instant.now()
                                )
                                .build()
                );

        assertEquals(
                PaymentStatus.COMPLETED,
                payment.getStatus()
        );

        verify(paymentSagaMetrics)
                .recordInvalidTransition();

        verify(
                paymentStateMachine,
                never()
        ).transition(
                any(),
                any()
        );
    }

    @Test
    void failedPaymentCannotBecomeCompleted() {

        UUID paymentId =
                UUID.randomUUID();

        Payment payment =
                payment(
                        paymentId,
                        PaymentStatus.FAILED
                );

        when(
                paymentRepository
                        .findByIdForUpdate(
                                paymentId
                        )
        ).thenReturn(
                Optional.of(payment)
        );

        paymentService
                .handleLedgerCompleted(
                        LedgerCompletedEvent.builder()
                                .paymentId(
                                        paymentId
                                )
                                .ledgerTransactionId(
                                        UUID.randomUUID()
                                )
                                .completedAt(
                                        Instant.now()
                                )
                                .build()
                );

        assertEquals(
                PaymentStatus.FAILED,
                payment.getStatus()
        );

        verify(paymentSagaMetrics)
                .recordInvalidTransition();

        verify(
                paymentStateMachine,
                never()
        ).transition(
                any(),
                any()
        );
    }

    @Test
    void unknownPaymentIsHandledExplicitly() {

        UUID paymentId =
                UUID.randomUUID();

        when(
                paymentRepository
                        .findByIdForUpdate(
                                paymentId
                        )
        ).thenReturn(
                Optional.empty()
        );

        assertThrows(
                PaymentNotFoundException.class,
                () ->
                        paymentService
                                .handleLedgerCompleted(
                                        LedgerCompletedEvent
                                                .builder()
                                                .paymentId(
                                                        paymentId
                                                )
                                                .ledgerTransactionId(
                                                        UUID.randomUUID()
                                                )
                                                .completedAt(
                                                        Instant.now()
                                                )
                                                .build()
                                )
        );

        verify(paymentSagaMetrics)
                .recordUnknownPayment();
    }

    private static Payment payment(
            UUID id,
            PaymentStatus status
    ) {

        return Payment.builder()
                .id(id)
                .senderId(UUID.randomUUID())
                .receiverId(UUID.randomUUID())
                .status(status)
                .build();
    }
}