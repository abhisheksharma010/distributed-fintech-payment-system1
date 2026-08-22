package com.fintech.notification.consumer;

import com.fintech.notification.event.PaymentCompletedEvent;
import com.fintech.notification.event.PaymentFailedEvent;
import com.fintech.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.*;

class PaymentEventConsumerTest {

    private NotificationService notificationService;

    private PaymentEventConsumer consumer;

    @BeforeEach
    void setUp() {

        notificationService =
                mock(
                        NotificationService.class
                );

        consumer =
                new PaymentEventConsumer(
                        notificationService
                );
    }

    @Test
    void completedEventDelegatesToNotificationService() {

        PaymentCompletedEvent event =
                PaymentCompletedEvent.builder()
                        .paymentId(
                                UUID.randomUUID()
                        )
                        .senderId(
                                UUID.randomUUID()
                        )
                        .build();

        consumer.consumeCompleted(event);

        verify(notificationService)
                .processCompleted(event);
    }

    @Test
    void failedEventDelegatesToNotificationService() {

        PaymentFailedEvent event =
                PaymentFailedEvent.builder()
                        .paymentId(
                                UUID.randomUUID()
                        )
                        .senderId(
                                UUID.randomUUID()
                        )
                        .reason(
                                "ACCOUNT_INACTIVE"
                        )
                        .build();

        consumer.consumeFailed(event);

        verify(notificationService)
                .processFailed(event);
    }
}