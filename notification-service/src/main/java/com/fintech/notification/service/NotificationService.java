package com.fintech.notification.service;

import com.fintech.notification.entity.Notification;
import com.fintech.notification.entity.NotificationChannel;
import com.fintech.notification.entity.NotificationStatus;
import com.fintech.notification.entity.NotificationType;
import com.fintech.notification.event.PaymentCompletedEvent;
import com.fintech.notification.event.PaymentFailedEvent;
import com.fintech.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private static final Set<String> SAFE_FAILURE_REASONS =
            Set.of(
                    "ACCOUNT_NOT_FOUND",
                    "ACCOUNT_INACTIVE",
                    "INSUFFICIENT_BALANCE",
                    "CURRENCY_MISMATCH",
                    "INVALID_TRANSFER",
                    "PROCESSING_TIMEOUT"
            );

    private final NotificationRepository notificationRepository;
    private final NotificationPersistenceService persistenceService;
    private final NotificationMetrics notificationMetrics;

    public void processCompleted(
            PaymentCompletedEvent event
    ) {

        validateCompletedEvent(event);

        createNotification(
                event.getPaymentId(),
                event.getSenderId(),
                NotificationType.PAYMENT_COMPLETED,
                "Payment completed successfully"
        );
    }

    public void processFailed(
            PaymentFailedEvent event
    ) {

        validateFailedEvent(event);

        createNotification(
                event.getPaymentId(),
                event.getSenderId(),
                NotificationType.PAYMENT_FAILED,
                "Payment failed: "
                        + safeFailureReason(
                        event.getReason()
                )
        );
    }

    private void createNotification(
            UUID paymentId,
            UUID userId,
            NotificationType type,
            String message
    ) {

        if (
                notificationRepository
                        .existsByPaymentIdAndType(
                                paymentId,
                                type
                        )
        ) {

            recordDuplicate(
                    paymentId,
                    type
            );

            return;
        }

        LocalDateTime now =
                LocalDateTime.now();

        Notification notification =
                Notification.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .paymentId(paymentId)
                        .type(type)
                        .channel(
                                NotificationChannel.MOCK
                        )
                        .status(
                                NotificationStatus.SENT
                        )
                        .message(message)
                        .createdAt(now)
                        .sentAt(now)
                        .build();

        try {

            Notification saved =
                    persistenceService.save(
                            notification
                    );

            notificationMetrics
                    .recordCreated();

            notificationMetrics
                    .recordSent();

            log.info(
                    "Mock notification delivered paymentId={} notificationType={} notificationId={} status={}",
                    paymentId,
                    type,
                    saved.getId(),
                    saved.getStatus()
            );

        } catch (
                DataIntegrityViolationException ex
        ) {

            /*
             * Another consumer committed the same
             * paymentId + type while this consumer
             * was processing.
             */
            recordDuplicate(
                    paymentId,
                    type
            );
        }
    }

    private void recordDuplicate(
            UUID paymentId,
            NotificationType type
    ) {

        notificationMetrics
                .recordDuplicate();

        log.info(
                "Notification duplicate ignored paymentId={} notificationType={} action=IGNORED_DUPLICATE",
                paymentId,
                type
        );
    }

    private void validateCompletedEvent(
            PaymentCompletedEvent event
    ) {

        if (
                event == null
                        ||
                        event.getPaymentId() == null
                        ||
                        event.getSenderId() == null
        ) {

            throw new IllegalArgumentException(
                    "Malformed payments.completed event"
            );
        }
    }

    private void validateFailedEvent(
            PaymentFailedEvent event
    ) {

        if (
                event == null
                        ||
                        event.getPaymentId() == null
                        ||
                        event.getSenderId() == null
        ) {

            throw new IllegalArgumentException(
                    "Malformed payments.failed event"
            );
        }
    }

    private String safeFailureReason(
            String reason
    ) {

        if (
                reason != null
                        &&
                        SAFE_FAILURE_REASONS
                                .contains(reason)
        ) {

            return reason;
        }

        return "PAYMENT_FAILED";
    }
}