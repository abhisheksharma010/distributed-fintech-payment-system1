package com.fintech.payment.service;

import com.fintech.payment.dto.CreatePaymentRequest;
import com.fintech.payment.entity.Payment;
import com.fintech.payment.entity.PaymentStatus;
import com.fintech.payment.event.LedgerCompletedEvent;
import com.fintech.payment.event.LedgerFailedEvent;
import com.fintech.payment.exception.IdempotencyKeyReusedException;
import com.fintech.payment.producer.PaymentEventProducer;
import com.fintech.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import com.fintech.payment.exception.PaymentAccessDeniedException;
import com.fintech.payment.exception.PaymentNotFoundException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentCreationService paymentCreationService;

    @Mock
    private PaymentStateMachine paymentStateMachine;

    @Mock
    private PaymentEventProducer paymentEventProducer;

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
    }

    @Test
    void createsNewPaymentWhenIdempotencyKeyDoesNotExist() {

        CreatePaymentRequest request =
                validRequest();

        Payment created =
                paymentFrom(
                        request,
                        PaymentStatus.PROCESSING
                );

        when(
                paymentRepository.findByIdempotencyKey(
                        request.getIdempotencyKey()
                )
        ).thenReturn(
                Optional.empty()
        );

        when(
                paymentCreationService
                        .createNewPayment(request)
        ).thenReturn(created);

        var response =
                paymentService.createPayment(request);

        assertEquals(
                created.getId(),
                response.getPaymentId()
        );

        verify(paymentCreationService)
                .createNewPayment(request);
    }

    @Test
    void sameKeyAndSameRequestReturnsExistingPayment() {

        CreatePaymentRequest request =
                validRequest();

        Payment existing =
                paymentFrom(
                        request,
                        PaymentStatus.COMPLETED
                );

        when(
                paymentRepository.findByIdempotencyKey(
                        request.getIdempotencyKey()
                )
        ).thenReturn(
                Optional.of(existing)
        );

        var response =
                paymentService.createPayment(request);

        assertEquals(
                existing.getId(),
                response.getPaymentId()
        );

        assertEquals(
                PaymentStatus.COMPLETED,
                response.getStatus()
        );

        verifyNoInteractions(
                paymentCreationService
        );
    }

    @Test
    void sameKeyAndDifferentSenderIsRejected() {

        CreatePaymentRequest request =
                validRequest();

        Payment existing =
                paymentFrom(
                        request,
                        PaymentStatus.PROCESSING
                );

        request.setSenderId(
                UUID.randomUUID()
        );

        stubExisting(
                existing,
                request.getIdempotencyKey()
        );

        assertThrows(
                IdempotencyKeyReusedException.class,
                () ->
                        paymentService
                                .createPayment(request)
        );

        verifyNoInteractions(
                paymentCreationService
        );
    }

    @Test
    void sameKeyAndDifferentReceiverIsRejected() {

        CreatePaymentRequest request =
                validRequest();

        Payment existing =
                paymentFrom(
                        request,
                        PaymentStatus.PROCESSING
                );

        request.setReceiverId(
                UUID.randomUUID()
        );

        stubExisting(
                existing,
                request.getIdempotencyKey()
        );

        assertThrows(
                IdempotencyKeyReusedException.class,
                () ->
                        paymentService
                                .createPayment(request)
        );
    }

    @Test
    void sameKeyAndDifferentAmountIsRejected() {

        CreatePaymentRequest request =
                validRequest();

        Payment existing =
                paymentFrom(
                        request,
                        PaymentStatus.PROCESSING
                );

        request.setAmount(
                new BigDecimal("5000.00")
        );

        stubExisting(
                existing,
                request.getIdempotencyKey()
        );

        assertThrows(
                IdempotencyKeyReusedException.class,
                () ->
                        paymentService
                                .createPayment(request)
        );
    }

    @Test
    void sameKeyAndDifferentCurrencyIsRejected() {

        CreatePaymentRequest request =
                validRequest();

        Payment existing =
                paymentFrom(
                        request,
                        PaymentStatus.PROCESSING
                );

        request.setCurrency("USD");

        stubExisting(
                existing,
                request.getIdempotencyKey()
        );

        assertThrows(
                IdempotencyKeyReusedException.class,
                () ->
                        paymentService
                                .createPayment(request)
        );
    }

    @Test
    void losingConcurrentRequestReturnsWinningPayment() {

        CreatePaymentRequest request =
                validRequest();

        Payment winner =
                paymentFrom(
                        request,
                        PaymentStatus.PROCESSING
                );

        when(
                paymentRepository.findByIdempotencyKey(
                        request.getIdempotencyKey()
                )
        )
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));

        when(
                paymentCreationService
                        .createNewPayment(request)
        ).thenThrow(
                new DataIntegrityViolationException(
                        "duplicate key"
                )
        );

        var response =
                paymentService.createPayment(request);

        assertEquals(
                winner.getId(),
                response.getPaymentId()
        );
    }

    @Test
    void losingConcurrentDifferentRequestIsRejected() {

        CreatePaymentRequest winningRequest =
                validRequest();

        Payment winner =
                paymentFrom(
                        winningRequest,
                        PaymentStatus.PROCESSING
                );

        CreatePaymentRequest losingRequest =
                validRequest();

        losingRequest.setIdempotencyKey(
                winningRequest.getIdempotencyKey()
        );

        losingRequest.setSenderId(
                winningRequest.getSenderId()
        );

        losingRequest.setReceiverId(
                winningRequest.getReceiverId()
        );

        losingRequest.setAmount(
                new BigDecimal("5000.00")
        );

        when(
                paymentRepository.findByIdempotencyKey(
                        losingRequest.getIdempotencyKey()
                )
        )
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));

        when(
                paymentCreationService
                        .createNewPayment(losingRequest)
        ).thenThrow(
                new DataIntegrityViolationException(
                        "duplicate key"
                )
        );

        assertThrows(
                IdempotencyKeyReusedException.class,
                () ->
                        paymentService
                                .createPayment(losingRequest)
        );
    }

    @Test
    void duplicateLedgerCompletedDoesNotReemitCompletion() {

        UUID paymentId =
                UUID.randomUUID();

        Payment completed =
                Payment.builder()
                        .id(paymentId)
                        .status(
                                PaymentStatus.COMPLETED
                        )
                        .build();

        when(
                paymentRepository
                        .findByIdForUpdate(paymentId)
        ).thenReturn(
                Optional.of(completed)
        );

        paymentService.handleLedgerCompleted(
                LedgerCompletedEvent.builder()
                        .paymentId(paymentId)
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

        verifyNoInteractions(
                paymentEventProducer
        );
    }

    @Test
    void duplicateLedgerFailedDoesNotReemitFailure() {

        UUID paymentId =
                UUID.randomUUID();

        Payment failed =
                Payment.builder()
                        .id(paymentId)
                        .status(
                                PaymentStatus.FAILED
                        )
                        .build();

        when(
                paymentRepository
                        .findByIdForUpdate(paymentId)
        ).thenReturn(
                Optional.of(failed)
        );

        paymentService.handleLedgerFailed(
                LedgerFailedEvent.builder()
                        .paymentId(paymentId)
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

        verifyNoInteractions(
                paymentEventProducer
        );
    }

    @Test
    void rejectsSameSenderAndReceiverBeforeRepositoryLookup() {

        CreatePaymentRequest request =
                validRequest();

        request.setReceiverId(
                request.getSenderId()
        );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        paymentService
                                .createPayment(request)
        );

        verifyNoInteractions(
                paymentRepository,
                paymentCreationService
        );
    }

    @Test
    void senderCanRetrievePayment() {

        UUID senderId =
                UUID.randomUUID();

        Payment payment =
                payment(
                        senderId,
                        UUID.randomUUID(),
                        PaymentStatus.COMPLETED
                );

        when(
                paymentRepository.findById(
                        payment.getId()
                )
        ).thenReturn(
                Optional.of(payment)
        );

        var response =
                paymentService.getPayment(
                        payment.getId(),
                        senderId,
                        Set.of("USER")
                );

        assertEquals(
                payment.getId(),
                response.getPaymentId()
        );
    }
    @Test
    void receiverCanRetrievePayment() {

        UUID receiverId =
                UUID.randomUUID();

        Payment payment =
                payment(
                        UUID.randomUUID(),
                        receiverId,
                        PaymentStatus.COMPLETED
                );

        when(
                paymentRepository.findById(
                        payment.getId()
                )
        ).thenReturn(
                Optional.of(payment)
        );

        assertDoesNotThrow(
                () ->
                        paymentService.getPayment(
                                payment.getId(),
                                receiverId,
                                Set.of("USER")
                        )
        );
    }

    @Test
    void unrelatedUserCannotRetrievePayment() {

        Payment payment =
                payment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        PaymentStatus.COMPLETED
                );

        when(
                paymentRepository.findById(
                        payment.getId()
                )
        ).thenReturn(
                Optional.of(payment)
        );

        assertThrows(
                PaymentAccessDeniedException.class,
                () ->
                        paymentService.getPayment(
                                payment.getId(),
                                UUID.randomUUID(),
                                Set.of("USER")
                        )
        );
    }

    @Test
    void adminCanRetrieveAnyPayment() {

        Payment payment =
                payment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        PaymentStatus.COMPLETED
                );

        when(
                paymentRepository.findById(
                        payment.getId()
                )
        ).thenReturn(
                Optional.of(payment)
        );

        assertDoesNotThrow(
                () ->
                        paymentService.getPayment(
                                payment.getId(),
                                UUID.randomUUID(),
                                Set.of("ADMIN")
                        )
        );
    }

    @Test
    void missingPaymentThrowsDomainException() {

        UUID paymentId =
                UUID.randomUUID();

        when(
                paymentRepository.findById(
                        paymentId
                )
        ).thenReturn(
                Optional.empty()
        );

        assertThrows(
                PaymentNotFoundException.class,
                () ->
                        paymentService.getPayment(
                                paymentId,
                                UUID.randomUUID(),
                                Set.of("USER")
                        )
        );
    }

    @Test
    void failedPaymentResponseIncludesFailureReason() {

        UUID senderId =
                UUID.randomUUID();

        Payment payment =
                payment(
                        senderId,
                        UUID.randomUUID(),
                        PaymentStatus.FAILED
                );

        payment.setFailureReason(
                "INSUFFICIENT_BALANCE"
        );

        when(
                paymentRepository.findById(
                        payment.getId()
                )
        ).thenReturn(
                Optional.of(payment)
        );

        var response =
                paymentService.getPayment(
                        payment.getId(),
                        senderId,
                        Set.of("USER")
                );

        assertEquals(
                PaymentStatus.FAILED,
                response.getStatus()
        );

        assertEquals(
                "INSUFFICIENT_BALANCE",
                response.getFailureReason()
        );
    }

    @Test
    void myPaymentsUsesDatabasePagination() {

        UUID userId =
                UUID.randomUUID();

        Payment sent =
                payment(
                        userId,
                        UUID.randomUUID(),
                        PaymentStatus.COMPLETED
                );

        Payment received =
                payment(
                        UUID.randomUUID(),
                        userId,
                        PaymentStatus.PROCESSING
                );

        when(
                paymentRepository
                        .findBySenderIdOrReceiverId(
                                eq(userId),
                                eq(userId),
                                any(Pageable.class)
                        )
        ).thenReturn(
                new PageImpl<>(
                        List.of(
                                sent,
                                received
                        )
                )
        );

        var response =
                paymentService.getMyPayments(
                        userId,
                        0,
                        10
                );

        assertEquals(
                2,
                response.content().size()
        );

        verify(paymentRepository)
                .findBySenderIdOrReceiverId(
                        eq(userId),
                        eq(userId),
                        any(Pageable.class)
                );

        verify(
                paymentRepository,
                never()
        ).findAll();
    }

    @Test
    void invalidPageIsRejectedBeforeRepositoryQuery() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        paymentService
                                .getMyPayments(
                                        UUID.randomUUID(),
                                        -1,
                                        10
                                )
        );

        verifyNoInteractions(
                paymentRepository
        );
    }

    @Test
    void excessivePageSizeIsRejected() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        paymentService
                                .getMyPayments(
                                        UUID.randomUUID(),
                                        0,
                                        101
                                )
        );
    }

    private static Payment payment(
            UUID senderId,
            UUID receiverId,
            PaymentStatus status
    ) {

        LocalDateTime now =
                LocalDateTime.now();

        return Payment.builder()
                .id(UUID.randomUUID())
                .senderId(senderId)
                .receiverId(receiverId)
                .amount(
                        new BigDecimal(
                                "2000.00"
                        )
                )
                .currency("INR")
                .status(status)
                .idempotencyKey(
                        "test-"
                                + UUID.randomUUID()
                )
                .createdAt(
                        now.minusMinutes(2)
                )
                .updatedAt(now)
                .recoveryAttemptCount(0)
                .build();
    }



    private void stubExisting(
            Payment existing,
            String idempotencyKey
    ) {

        when(
                paymentRepository
                        .findByIdempotencyKey(
                                idempotencyKey
                        )
        ).thenReturn(
                Optional.of(existing)
        );
    }

    private static Payment paymentFrom(
            CreatePaymentRequest request,
            PaymentStatus status
    ) {

        return Payment.builder()
                .id(UUID.randomUUID())
                .senderId(request.getSenderId())
                .receiverId(request.getReceiverId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .idempotencyKey(
                        request.getIdempotencyKey()
                )
                .status(status)
                .build();
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
                "payment-abc-123"
        );

        return request;
    }
}