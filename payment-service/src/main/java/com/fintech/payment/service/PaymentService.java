package com.fintech.payment.service;

import com.fintech.payment.dto.CreatePaymentRequest;
import com.fintech.payment.dto.PaymentPageResponse;
import com.fintech.payment.dto.PaymentResponse;
import com.fintech.payment.entity.Payment;
import com.fintech.payment.entity.PaymentStatus;
import com.fintech.payment.event.LedgerCompletedEvent;
import com.fintech.payment.event.LedgerFailedEvent;
import com.fintech.payment.event.PaymentCompletedEvent;
import com.fintech.payment.event.PaymentFailedEvent;
import com.fintech.payment.exception.IdempotencyKeyReusedException;
import com.fintech.payment.exception.PaymentAccessDeniedException;
import com.fintech.payment.exception.PaymentNotFoundException;
import com.fintech.payment.producer.PaymentEventProducer;
import com.fintech.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private static final int MAX_PAGE_SIZE =
            100;

    private final PaymentRepository paymentRepository;
    private final PaymentCreationService paymentCreationService;
    private final PaymentStateMachine paymentStateMachine;
    private final PaymentEventProducer paymentEventProducer;

    private PaymentSagaMetrics paymentSagaMetrics =
            PaymentSagaMetrics.noop();

    @Autowired
    void setPaymentSagaMetrics(
            PaymentSagaMetrics paymentSagaMetrics
    ) {

        this.paymentSagaMetrics =
                paymentSagaMetrics;
    }

    public PaymentResponse createPayment(
            CreatePaymentRequest request
    ) {

        validateTransferParties(request);

        log.info(
                "Starting payment creation senderId={} receiverId={} amount={} idempotencyKey={}",
                request.getSenderId(),
                request.getReceiverId(),
                request.getAmount(),
                request.getIdempotencyKey()
        );

        return paymentRepository
                .findByIdempotencyKey(
                        request.getIdempotencyKey()
                )
                .map(
                        existing ->
                                resolveExistingPayment(
                                        existing,
                                        request
                                )
                )
                .orElseGet(
                        () ->
                                createOrResolveConcurrentRequest(
                                        request
                                )
                );
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(
            UUID paymentId,
            UUID authenticatedUserId,
            Set<String> roles
    ) {

        Payment payment =
                paymentRepository
                        .findById(paymentId)
                        .orElseThrow(
                                () ->
                                        new PaymentNotFoundException(
                                                paymentId
                                        )
                        );

        if (
                !isAdmin(roles)
                        &&
                        !authenticatedUserId.equals(
                                payment.getSenderId()
                        )
                        &&
                        !authenticatedUserId.equals(
                                payment.getReceiverId()
                        )
        ) {

            log.warn(
                    "Payment access denied paymentId={} userId={}",
                    paymentId,
                    authenticatedUserId
            );

            throw new PaymentAccessDeniedException(
                    paymentId
            );
        }

        return toResponse(payment);
    }

    @Transactional(readOnly = true)
    public PaymentPageResponse getMyPayments(
            UUID authenticatedUserId,
            int page,
            int size
    ) {

        validatePagination(
                page,
                size
        );

        PageRequest pageable =
                PageRequest.of(
                        page,
                        size,
                        Sort.by(
                                        Sort.Direction.DESC,
                                        "createdAt"
                                )
                                .and(
                                        Sort.by(
                                                Sort.Direction.DESC,
                                                "id"
                                        )
                                )
                );

        Page<Payment> payments =
                paymentRepository
                        .findBySenderIdOrReceiverId(
                                authenticatedUserId,
                                authenticatedUserId,
                                pageable
                        );

        return new PaymentPageResponse(
                payments
                        .getContent()
                        .stream()
                        .map(
                                this::toResponse
                        )
                        .toList(),

                payments.getNumber(),
                payments.getSize(),
                payments.getTotalElements(),
                payments.getTotalPages(),
                payments.isFirst(),
                payments.isLast()
        );
    }

    private PaymentResponse
    createOrResolveConcurrentRequest(
            CreatePaymentRequest request
    ) {

        try {

            Payment payment =
                    paymentCreationService
                            .createNewPayment(
                                    request
                            );

            return toResponse(payment);

        } catch (
                DataIntegrityViolationException ex
        ) {

            return paymentRepository
                    .findByIdempotencyKey(
                            request.getIdempotencyKey()
                    )
                    .map(
                            existing ->
                                    resolveExistingPayment(
                                            existing,
                                            request
                                    )
                    )
                    .orElseThrow(
                            () -> ex
                    );
        }
    }

    private PaymentResponse resolveExistingPayment(
            Payment existing,
            CreatePaymentRequest request
    ) {

        if (
                !sameOperation(
                        existing,
                        request
                )
        ) {

            throw new IdempotencyKeyReusedException(
                    request.getIdempotencyKey()
            );
        }

        log.info(
                "Returning existing payment paymentId={} idempotencyKey={} status={}",
                existing.getId(),
                existing.getIdempotencyKey(),
                existing.getStatus()
        );

        return toResponse(existing);
    }

    private boolean sameOperation(
            Payment existing,
            CreatePaymentRequest request
    ) {

        return Objects.equals(
                existing.getSenderId(),
                request.getSenderId()
        )
                &&
                Objects.equals(
                        existing.getReceiverId(),
                        request.getReceiverId()
                )
                &&
                existing.getAmount()
                        != null
                &&
                request.getAmount()
                        != null
                &&
                existing.getAmount()
                        .compareTo(
                                request.getAmount()
                        ) == 0
                &&
                Objects.equals(
                        existing.getCurrency(),
                        request.getCurrency()
                );
    }

    private void validateTransferParties(
            CreatePaymentRequest request
    ) {

        if (
                request == null
                        ||
                        request.getSenderId() == null
                        ||
                        request.getReceiverId() == null
        ) {

            throw new IllegalArgumentException(
                    "senderId and receiverId are required"
            );
        }

        if (
                request.getSenderId()
                        .equals(
                                request.getReceiverId()
                        )
        ) {

            throw new IllegalArgumentException(
                    "senderId and receiverId must be different"
            );
        }
    }

    private void validatePagination(
            int page,
            int size
    ) {

        if (page < 0) {

            throw new IllegalArgumentException(
                    "page must be greater than or equal to 0"
            );
        }

        if (
                size < 1
                        ||
                        size > MAX_PAGE_SIZE
        ) {

            throw new IllegalArgumentException(
                    "size must be between 1 and "
                            + MAX_PAGE_SIZE
            );
        }
    }

    private boolean isAdmin(
            Set<String> roles
    ) {

        return roles != null
                &&
                roles.contains(
                        "ADMIN"
                );
    }

    private PaymentResponse toResponse(
            Payment payment
    ) {

        return PaymentResponse.builder()
                .paymentId(
                        payment.getId()
                )
                .senderId(
                        payment.getSenderId()
                )
                .receiverId(
                        payment.getReceiverId()
                )
                .amount(
                        payment.getAmount()
                )
                .currency(
                        payment.getCurrency()
                )
                .status(
                        payment.getStatus()
                )
                .failureReason(
                        payment.getFailureReason()
                )
                .createdAt(
                        payment.getCreatedAt()
                )
                .updatedAt(
                        payment.getUpdatedAt()
                )
                .build();
    }

    @Transactional
    public void updatePaymentStatus(
            UUID paymentId,
            PaymentStatus newStatus
    ) {

        log.info(
                "Updating payment status paymentId={} newStatus={}",
                paymentId,
                newStatus
        );

        Payment payment =
                paymentRepository
                        .findByIdForUpdate(
                                paymentId
                        )
                        .orElseThrow(
                                () ->
                                        new PaymentNotFoundException(
                                                paymentId
                                        )
                        );

        if (
                payment.getStatus()
                        == newStatus
        ) {
            return;
        }

        paymentStateMachine.transition(
                payment,
                newStatus
        );

        paymentRepository.save(
                payment
        );
    }

    @Transactional
    public void handleLedgerCompleted(
            LedgerCompletedEvent event
    ) {

        UUID paymentId =
                requirePaymentId(event);

        Payment payment =
                findPaymentForSagaUpdate(
                        paymentId
                );

        if (
                payment.getStatus()
                        ==
                        PaymentStatus.COMPLETED
        ) {

            log.info(
                    "Duplicate ledger.completed ignored paymentId={} ledgerTransactionId={}",
                    paymentId,
                    event.getLedgerTransactionId()
            );

            return;
        }

        if (
                payment.getStatus()
                        !=
                        PaymentStatus.PROCESSING
        ) {

            paymentSagaMetrics
                    .recordInvalidTransition();

            log.warn(
                    "Conflicting/out-of-order ledger.completed ignored paymentId={} currentStatus={} ledgerTransactionId={}",
                    paymentId,
                    payment.getStatus(),
                    event.getLedgerTransactionId()
            );

            return;
        }

        validateCompletedMetadata(
                event
        );

        paymentStateMachine.transition(
                payment,
                PaymentStatus.COMPLETED
        );

        payment.setFailureReason(null);

        paymentRepository.save(
                payment
        );

        paymentSagaMetrics
                .recordCompleted();

        log.info(
                "Saga transition completed paymentId={} transition=PROCESSING_TO_COMPLETED ledgerTransactionId={} completedAt={}",
                paymentId,
                event.getLedgerTransactionId(),
                event.getCompletedAt()
        );

        paymentEventProducer
                .sendPaymentCompleted(
                        PaymentCompletedEvent.builder()
                                .paymentId(
                                        paymentId
                                )
                                .senderId(
                                        payment.getSenderId()
                                )
                                .completedAt(
                                        event.getCompletedAt()
                                )
                                .build()
                );
    }

    @Transactional
    public void handleLedgerFailed(
            LedgerFailedEvent event
    ) {

        UUID paymentId =
                requirePaymentId(event);

        Payment payment =
                findPaymentForSagaUpdate(
                        paymentId
                );

        if (
                payment.getStatus()
                        ==
                        PaymentStatus.FAILED
        ) {

            log.info(
                    "Duplicate ledger.failed ignored paymentId={} reason={}",
                    paymentId,
                    event.getReason()
            );

            return;
        }

        if (
                payment.getStatus()
                        !=
                        PaymentStatus.PROCESSING
        ) {

            paymentSagaMetrics
                    .recordInvalidTransition();

            log.warn(
                    "Conflicting/out-of-order ledger.failed ignored paymentId={} currentStatus={} reason={}",
                    paymentId,
                    payment.getStatus(),
                    event.getReason()
            );

            return;
        }

        validateFailedMetadata(
                event
        );

        paymentStateMachine.transition(
                payment,
                PaymentStatus.FAILED
        );

        payment.setFailureReason(
                event.getReason()
        );

        paymentRepository.save(
                payment
        );

        paymentSagaMetrics
                .recordFailed();

        log.info(
                "Saga transition failed paymentId={} transition=PROCESSING_TO_FAILED reason={} failedAt={}",
                paymentId,
                event.getReason(),
                event.getFailedAt()
        );

        paymentEventProducer
                .sendPaymentFailed(
                        PaymentFailedEvent.builder()
                                .paymentId(
                                        paymentId
                                )
                                .senderId(
                                        payment.getSenderId()
                                )
                                .reason(
                                        event.getReason()
                                )
                                .failedAt(
                                        event.getFailedAt()
                                )
                                .build()
                );
    }

    private Payment findPaymentForSagaUpdate(
            UUID paymentId
    ) {

        return paymentRepository
                .findByIdForUpdate(
                        paymentId
                )
                .orElseThrow(
                        () -> {

                            paymentSagaMetrics
                                    .recordUnknownPayment();

                            log.error(
                                    "Ledger result received for unknown paymentId={}",
                                    paymentId
                            );

                            return new PaymentNotFoundException(
                                    paymentId
                            );
                        }
                );
    }

    private UUID requirePaymentId(
            LedgerCompletedEvent event
    ) {

        if (
                event == null
                        ||
                        event.getPaymentId() == null
        ) {

            paymentSagaMetrics
                    .recordInvalidTransition();

            throw new IllegalArgumentException(
                    "Malformed ledger.completed event"
            );
        }

        return event.getPaymentId();
    }

    private UUID requirePaymentId(
            LedgerFailedEvent event
    ) {

        if (
                event == null
                        ||
                        event.getPaymentId() == null
        ) {

            paymentSagaMetrics
                    .recordInvalidTransition();

            throw new IllegalArgumentException(
                    "Malformed ledger.failed event"
            );
        }

        return event.getPaymentId();
    }

    private void validateCompletedMetadata(
            LedgerCompletedEvent event
    ) {

        if (
                event.getLedgerTransactionId()
                        == null
                        ||
                        event.getCompletedAt()
                                == null
        ) {

            paymentSagaMetrics
                    .recordInvalidTransition();

            throw new IllegalArgumentException(
                    "Malformed ledger.completed event"
            );
        }
    }

    private void validateFailedMetadata(
            LedgerFailedEvent event
    ) {

        if (
                event.getReason() == null
                        ||
                        event.getReason()
                                .isBlank()
                        ||
                        event.getFailedAt()
                                == null
        ) {

            paymentSagaMetrics
                    .recordInvalidTransition();

            throw new IllegalArgumentException(
                    "Malformed ledger.failed event"
            );
        }
    }
}