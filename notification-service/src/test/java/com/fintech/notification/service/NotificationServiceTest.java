package com.fintech.notification.service;

import com.fintech.notification.entity.Notification;
import com.fintech.notification.entity.NotificationStatus;
import com.fintech.notification.entity.NotificationType;
import com.fintech.notification.event.PaymentCompletedEvent;
import com.fintech.notification.event.PaymentFailedEvent;
import com.fintech.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationPersistenceService persistenceService;

    @Mock
    private NotificationMetrics notificationMetrics;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {

        notificationService =
                new NotificationService(
                        notificationRepository,
                        persistenceService,
                        notificationMetrics
                );
    }

    @Test
    void completionCreatesSentNotificationForSender() {

        UUID paymentId =
                UUID.randomUUID();

        UUID senderId =
                UUID.randomUUID();

        when(
                notificationRepository
                        .existsByPaymentIdAndType(
                                paymentId,
                                NotificationType.PAYMENT_COMPLETED
                        )
        ).thenReturn(false);

        when(
                persistenceService.save(
                        any(Notification.class)
                )
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );

        notificationService
                .processCompleted(
                        PaymentCompletedEvent.builder()
                                .paymentId(paymentId)
                                .senderId(senderId)
                                .completedAt(
                                        Instant.now()
                                )
                                .build()
                );

        ArgumentCaptor<Notification> captor =
                ArgumentCaptor.forClass(
                        Notification.class
                );

        verify(persistenceService)
                .save(
                        captor.capture()
                );

        Notification notification =
                captor.getValue();

        assertEquals(
                paymentId,
                notification.getPaymentId()
        );

        assertEquals(
                senderId,
                notification.getUserId()
        );

        assertEquals(
                NotificationType.PAYMENT_COMPLETED,
                notification.getType()
        );

        assertEquals(
                NotificationStatus.SENT,
                notification.getStatus()
        );

        assertEquals(
                "Payment completed successfully",
                notification.getMessage()
        );

        assertNotNull(
                notification.getCreatedAt()
        );

        assertNotNull(
                notification.getSentAt()
        );

        verify(notificationMetrics)
                .recordCreated();

        verify(notificationMetrics)
                .recordSent();
    }

    @Test
    void failureCreatesSentNotificationWithBusinessReason() {

        UUID paymentId =
                UUID.randomUUID();

        UUID senderId =
                UUID.randomUUID();

        when(
                notificationRepository
                        .existsByPaymentIdAndType(
                                paymentId,
                                NotificationType.PAYMENT_FAILED
                        )
        ).thenReturn(false);

        when(
                persistenceService.save(
                        any(Notification.class)
                )
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );

        notificationService
                .processFailed(
                        PaymentFailedEvent.builder()
                                .paymentId(paymentId)
                                .senderId(senderId)
                                .reason(
                                        "INSUFFICIENT_BALANCE"
                                )
                                .failedAt(
                                        Instant.now()
                                )
                                .build()
                );

        ArgumentCaptor<Notification> captor =
                ArgumentCaptor.forClass(
                        Notification.class
                );

        verify(persistenceService)
                .save(
                        captor.capture()
                );

        Notification notification =
                captor.getValue();

        assertEquals(
                NotificationType.PAYMENT_FAILED,
                notification.getType()
        );

        assertEquals(
                senderId,
                notification.getUserId()
        );

        assertEquals(
                "Payment failed: INSUFFICIENT_BALANCE",
                notification.getMessage()
        );
    }

    @Test
    void unsafeFailureReasonIsNotExposed() {

        UUID paymentId =
                UUID.randomUUID();

        when(
                notificationRepository
                        .existsByPaymentIdAndType(
                                paymentId,
                                NotificationType.PAYMENT_FAILED
                        )
        ).thenReturn(false);

        when(
                persistenceService.save(
                        any(Notification.class)
                )
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );

        notificationService
                .processFailed(
                        PaymentFailedEvent.builder()
                                .paymentId(paymentId)
                                .senderId(
                                        UUID.randomUUID()
                                )
                                .reason(
                                        "PSQLException: connection refused"
                                )
                                .failedAt(
                                        Instant.now()
                                )
                                .build()
                );

        ArgumentCaptor<Notification> captor =
                ArgumentCaptor.forClass(
                        Notification.class
                );

        verify(persistenceService)
                .save(
                        captor.capture()
                );

        assertEquals(
                "Payment failed: PAYMENT_FAILED",
                captor.getValue()
                        .getMessage()
        );

        assertFalse(
                captor.getValue()
                        .getMessage()
                        .contains(
                                "PSQLException"
                        )
        );
    }

    @Test
    void existingCompletionIsIgnoredAsDuplicate() {

        UUID paymentId =
                UUID.randomUUID();

        when(
                notificationRepository
                        .existsByPaymentIdAndType(
                                paymentId,
                                NotificationType.PAYMENT_COMPLETED
                        )
        ).thenReturn(true);

        notificationService
                .processCompleted(
                        PaymentCompletedEvent.builder()
                                .paymentId(paymentId)
                                .senderId(
                                        UUID.randomUUID()
                                )
                                .build()
                );

        verifyNoInteractions(
                persistenceService
        );

        verify(notificationMetrics)
                .recordDuplicate();
    }

    @Test
    void databaseDuplicateRaceIsHandledAsDuplicate() {

        UUID paymentId =
                UUID.randomUUID();

        when(
                notificationRepository
                        .existsByPaymentIdAndType(
                                paymentId,
                                NotificationType.PAYMENT_COMPLETED
                        )
        ).thenReturn(false);

        when(
                persistenceService.save(
                        any(Notification.class)
                )
        ).thenThrow(
                new DataIntegrityViolationException(
                        "duplicate"
                )
        );

        assertDoesNotThrow(
                () ->
                        notificationService
                                .processCompleted(
                                        PaymentCompletedEvent
                                                .builder()
                                                .paymentId(
                                                        paymentId
                                                )
                                                .senderId(
                                                        UUID.randomUUID()
                                                )
                                                .build()
                                )
        );

        verify(notificationMetrics)
                .recordDuplicate();

        verify(
                notificationMetrics,
                never()
        ).recordCreated();
    }

    @Test
    void missingPaymentIdIsRejected() {

        PaymentCompletedEvent event =
                PaymentCompletedEvent.builder()
                        .senderId(
                                UUID.randomUUID()
                        )
                        .build();

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        notificationService
                                .processCompleted(
                                        event
                                )
        );

        verifyNoInteractions(
                notificationRepository,
                persistenceService
        );
    }

    @Test
    void missingSenderIsRejected() {

        PaymentFailedEvent event =
                PaymentFailedEvent.builder()
                        .paymentId(
                                UUID.randomUUID()
                        )
                        .reason(
                                "ACCOUNT_INACTIVE"
                        )
                        .build();

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        notificationService
                                .processFailed(
                                        event
                                )
        );

        verifyNoInteractions(
                notificationRepository,
                persistenceService
        );
    }
}