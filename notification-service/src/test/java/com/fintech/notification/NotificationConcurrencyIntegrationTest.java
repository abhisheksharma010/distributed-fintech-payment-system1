package com.fintech.notification;

import com.fintech.notification.entity.NotificationType;
import com.fintech.notification.event.PaymentCompletedEvent;
import com.fintech.notification.repository.NotificationRepository;
import com.fintech.notification.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        properties = {
                "spring.kafka.listener.auto-startup=false"
        }
)
class NotificationConcurrencyIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    private UUID testPaymentId;

    @AfterEach
    void cleanup() {

        if (testPaymentId == null) {
            return;
        }

        notificationRepository
                .findAll()
                .stream()
                .filter(
                        notification ->
                                testPaymentId.equals(
                                        notification
                                                .getPaymentId()
                                )
                )
                .forEach(
                        notificationRepository::delete
                );
    }

    @Test
    void concurrentDuplicateCompletionCreatesExactlyOneNotification()
            throws Exception {

        testPaymentId =
                UUID.randomUUID();

        PaymentCompletedEvent event =
                PaymentCompletedEvent.builder()
                        .paymentId(
                                testPaymentId
                        )
                        .senderId(
                                UUID.randomUUID()
                        )
                        .completedAt(
                                Instant.now()
                        )
                        .build();

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {

            List<Callable<Void>> tasks =
                    List.of(
                            () -> {
                                notificationService
                                        .processCompleted(
                                                event
                                        );
                                return null;
                            },

                            () -> {
                                notificationService
                                        .processCompleted(
                                                event
                                        );
                                return null;
                            }
                    );

            List<Future<Void>> futures =
                    executor.invokeAll(tasks);

            for (
                    Future<Void> future :
                    futures
            ) {
                future.get();
            }

            assertEquals(
                    1,
                    notificationRepository
                            .countByPaymentIdAndType(
                                    testPaymentId,
                                    NotificationType
                                            .PAYMENT_COMPLETED
                            )
            );

        } finally {

            executor.shutdownNow();
        }
    }
}