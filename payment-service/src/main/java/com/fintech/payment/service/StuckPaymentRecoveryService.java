package com.fintech.payment.service;

import com.fintech.payment.config.PaymentRecoveryProperties;
import com.fintech.payment.dto.LedgerRecoveryResponse;
import com.fintech.payment.entity.OutboxEvent;
import com.fintech.payment.entity.Payment;
import com.fintech.payment.entity.PaymentStatus;
import com.fintech.payment.event.LedgerCompletedEvent;
import com.fintech.payment.event.LedgerFailedEvent;
import com.fintech.payment.repository.OutboxRepository;
import com.fintech.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class StuckPaymentRecoveryService {

    private static final String PAYMENT_CREATED =
            "PAYMENT_CREATED";

    private static final String PROCESSING_TIMEOUT =
            "PROCESSING_TIMEOUT";

    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;
    private final PaymentService paymentService;
    private final LedgerRecoveryClient ledgerRecoveryClient;
    private final PaymentRecoveryProperties properties;
    private final StuckPaymentRecoveryMetrics metrics;

    @Transactional
    public int recoverStuckPayments() {

        LocalDateTime now =
                LocalDateTime.now();

        LocalDateTime processingCutoff =
                now.minus(
                        Duration.ofMillis(
                                properties.getProcessingTimeoutMs()
                        )
                );

        LocalDateTime retryCutoff =
                now.minus(
                        Duration.ofMillis(
                                properties.getRetryIntervalMs()
                        )
                );

        List<Payment> payments =
                paymentRepository
                        .findStuckPaymentsForRecovery(
                                processingCutoff,
                                retryCutoff,
                                properties.getBatchSize()
                        );

        for (Payment payment : payments) {
            recoverOne(payment, now);
        }

        return payments.size();
    }

    private void recoverOne(
            Payment payment,
            LocalDateTime now
    ) {

        if (
                payment.getStatus()
                        != PaymentStatus.PROCESSING
        ) {
            return;
        }

        metrics.recordDetected();

        long ageSeconds =
                Duration.between(
                                payment.getUpdatedAt(),
                                now
                        )
                        .getSeconds();

        LedgerRecoveryResponse ledgerResult;

        try {

            ledgerResult =
                    ledgerRecoveryClient.lookup(
                            payment.getId()
                    );

        } catch (
                LedgerRecoveryClient
                        .LedgerRecoveryUnavailableException ex
        ) {

            log.warn(
                    "Stuck payment recovery deferred paymentId={} currentStatus={} ageSeconds={} recoveryAttempt={} ledgerState=UNAVAILABLE action=RETRY_LATER",
                    payment.getId(),
                    payment.getStatus(),
                    ageSeconds,
                    payment.getRecoveryAttemptCount()
            );

            return;
        }

        int attempt =
                payment.getRecoveryAttemptCount()
                        + 1;

        payment.setRecoveryAttemptCount(
                attempt
        );

        payment.setLastRecoveryAt(
                now
        );

        paymentRepository.save(payment);

        switch (ledgerResult.state()) {

            case PROCESSED ->
                    recoverCompleted(
                            payment,
                            ledgerResult,
                            ageSeconds,
                            attempt
                    );

            case FAILED ->
                    recoverKnownFailure(
                            payment,
                            ledgerResult,
                            ageSeconds,
                            attempt
                    );

            case NOT_FOUND ->
                    recoverMissingLedgerResult(
                            payment,
                            ageSeconds,
                            attempt
                    );
        }
    }

    private void recoverCompleted(
            Payment payment,
            LedgerRecoveryResponse ledgerResult,
            long ageSeconds,
            int attempt
    ) {

        if (
                ledgerResult.ledgerTransactionId()
                        == null
        ) {

            throw new IllegalStateException(
                    "Ledger returned PROCESSED without ledgerTransactionId paymentId="
                            + payment.getId()
            );
        }

        paymentService.handleLedgerCompleted(
                LedgerCompletedEvent.builder()
                        .paymentId(
                                payment.getId()
                        )
                        .ledgerTransactionId(
                                ledgerResult
                                        .ledgerTransactionId()
                        )
                        .completedAt(
                                toInstant(
                                        ledgerResult
                                                .resolvedAt()
                                )
                        )
                        .build()
        );

        metrics.recordRecovered();

        log.info(
                "Stuck payment recovered paymentId={} currentStatus=PROCESSING ageSeconds={} recoveryAttempt={} ledgerState=PROCESSED action=RECOVER_COMPLETED",
                payment.getId(),
                ageSeconds,
                attempt
        );
    }

    private void recoverKnownFailure(
            Payment payment,
            LedgerRecoveryResponse ledgerResult,
            long ageSeconds,
            int attempt
    ) {

        if (
                ledgerResult.failureReason()
                        == null
                        ||
                        ledgerResult
                                .failureReason()
                                .isBlank()
        ) {

            throw new IllegalStateException(
                    "Ledger returned FAILED without failureReason paymentId="
                            + payment.getId()
            );
        }

        paymentService.handleLedgerFailed(
                LedgerFailedEvent.builder()
                        .paymentId(
                                payment.getId()
                        )
                        .reason(
                                ledgerResult
                                        .failureReason()
                        )
                        .failedAt(
                                toInstant(
                                        ledgerResult
                                                .resolvedAt()
                                )
                        )
                        .build()
        );

        metrics.recordRecovered();

        log.info(
                "Stuck payment recovered paymentId={} currentStatus=PROCESSING ageSeconds={} recoveryAttempt={} ledgerState=FAILED action=RECOVER_FAILED reason={}",
                payment.getId(),
                ageSeconds,
                attempt,
                ledgerResult.failureReason()
        );
    }

    private void recoverMissingLedgerResult(
            Payment payment,
            long ageSeconds,
            int attempt
    ) {

        if (
                attempt
                        >= properties.getMaxAttempts()
        ) {

            metrics.recordFailed();

            log.error(
                    "Stuck payment recovery exhausted paymentId={} currentStatus=PROCESSING ageSeconds={} recoveryAttempt={} ledgerState=NOT_FOUND action=MANUAL_REVIEW reason=OUTCOME_UNKNOWN",
                    payment.getId(),
                    ageSeconds,
                    attempt
            );

            return;
        }

        OutboxEvent event =
                outboxRepository
                        .findFirstByAggregateIdAndEventTypeOrderByCreatedAtAsc(
                                payment.getId(),
                                PAYMENT_CREATED
                        )
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Payment outbox event missing paymentId="
                                                        + payment.getId()
                                        )
                        );

        event.setPublished(false);
        event.setPublishedAt(null);
        event.setLastError(null);

        outboxRepository.save(event);

        metrics.recordRetry();

        log.info(
                "Stuck payment retry requested paymentId={} currentStatus=PROCESSING ageSeconds={} recoveryAttempt={} ledgerState=NOT_FOUND action=REPUBLISH_OUTBOX eventId={}",
                payment.getId(),
                ageSeconds,
                attempt,
                event.getId()
        );
    }

    private Instant toInstant(
            LocalDateTime value
    ) {

        if (value == null) {
            return Instant.now();
        }

        return value.toInstant(
                ZoneOffset.UTC
        );
    }
}