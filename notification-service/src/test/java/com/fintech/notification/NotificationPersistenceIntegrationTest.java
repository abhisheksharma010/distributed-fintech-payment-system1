package com.fintech.notification;

import com.fintech.notification.entity.Notification;
import com.fintech.notification.entity.NotificationChannel;
import com.fintech.notification.entity.NotificationStatus;
import com.fintech.notification.entity.NotificationType;
import com.fintech.notification.repository.NotificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        properties = {
                "spring.kafka.listener.auto-startup=false"
        }
)
class NotificationPersistenceIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    private final List<UUID> testNotificationIds =
            new ArrayList<>();

    @AfterEach
    void cleanup() {

        testNotificationIds.forEach(
                id ->
                        notificationRepository
                                .findById(id)
                                .ifPresent(
                                        notificationRepository::delete
                                )
        );

        testNotificationIds.clear();
    }

    @Test
    void notificationPersistsInPostgres() {

        Notification notification =
                notification(
                        UUID.randomUUID(),
                        NotificationType.PAYMENT_COMPLETED
                );

        testNotificationIds.add(
                notification.getId()
        );

        notificationRepository
                .saveAndFlush(
                        notification
                );

        Notification reloaded =
                notificationRepository
                        .findById(
                                notification.getId()
                        )
                        .orElseThrow();

        assertEquals(
                notification.getPaymentId(),
                reloaded.getPaymentId()
        );

        assertEquals(
                NotificationStatus.SENT,
                reloaded.getStatus()
        );
    }

    @Test
    void databaseRejectsDuplicatePaymentAndType() {

        UUID paymentId =
                UUID.randomUUID();

        Notification first =
                notification(
                        paymentId,
                        NotificationType.PAYMENT_COMPLETED
                );

        Notification second =
                notification(
                        paymentId,
                        NotificationType.PAYMENT_COMPLETED
                );

        testNotificationIds.add(
                first.getId()
        );

        testNotificationIds.add(
                second.getId()
        );

        notificationRepository
                .saveAndFlush(first);

        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        notificationRepository
                                .saveAndFlush(
                                        second
                                )
        );

        assertEquals(
                1,
                notificationRepository
                        .countByPaymentIdAndType(
                                paymentId,
                                NotificationType
                                        .PAYMENT_COMPLETED
                        )
        );
    }

    @Test
    void completedAndFailedTypesCanCoexistForSamePayment() {

        UUID paymentId =
                UUID.randomUUID();

        Notification completed =
                notification(
                        paymentId,
                        NotificationType.PAYMENT_COMPLETED
                );

        Notification failed =
                notification(
                        paymentId,
                        NotificationType.PAYMENT_FAILED
                );

        testNotificationIds.add(
                completed.getId()
        );

        testNotificationIds.add(
                failed.getId()
        );

        notificationRepository
                .saveAndFlush(completed);

        notificationRepository
                .saveAndFlush(failed);

        assertEquals(
                1,
                notificationRepository
                        .countByPaymentIdAndType(
                                paymentId,
                                NotificationType
                                        .PAYMENT_COMPLETED
                        )
        );

        assertEquals(
                1,
                notificationRepository
                        .countByPaymentIdAndType(
                                paymentId,
                                NotificationType
                                        .PAYMENT_FAILED
                        )
        );
    }

    private static Notification notification(
            UUID paymentId,
            NotificationType type
    ) {

        LocalDateTime now =
                LocalDateTime.now();

        return Notification.builder()
                .id(UUID.randomUUID())
                .userId(
                        UUID.randomUUID()
                )
                .paymentId(paymentId)
                .type(type)
                .channel(
                        NotificationChannel.MOCK
                )
                .status(
                        NotificationStatus.SENT
                )
                .message(
                        type
                                ==
                                NotificationType
                                        .PAYMENT_COMPLETED
                                ?
                                "Payment completed successfully"
                                :
                                "Payment failed: PAYMENT_FAILED"
                )
                .createdAt(now)
                .sentAt(now)
                .build();
    }
}