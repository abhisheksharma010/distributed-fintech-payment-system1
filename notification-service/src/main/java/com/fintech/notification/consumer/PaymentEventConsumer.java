package com.fintech.notification.consumer;

import com.fintech.notification.event.PaymentCompletedEvent;
import com.fintech.notification.event.PaymentFailedEvent;
import com.fintech.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {

    private final NotificationService notificationService;

    @KafkaListener(
            topics = "${kafka.topics.payments-completed:payments.completed}",
            groupId = "${spring.kafka.consumer.group-id:notification-service-group}",
            properties = {
                    "spring.json.value.default.type=com.fintech.notification.event.PaymentCompletedEvent"
            }
    )
    public void consumeCompleted(
            PaymentCompletedEvent event
    ) {

        log.info(
                "payments.completed received paymentId={}",
                event == null
                        ? null
                        : event.getPaymentId()
        );

        notificationService
                .processCompleted(event);
    }

    @KafkaListener(
            topics = "${kafka.topics.payments-failed:payments.failed}",
            groupId = "${spring.kafka.consumer.group-id:notification-service-group}",
            properties = {
                    "spring.json.value.default.type=com.fintech.notification.event.PaymentFailedEvent"
            }
    )
    public void consumeFailed(
            PaymentFailedEvent event
    ) {

        log.info(
                "payments.failed received paymentId={}",
                event == null
                        ? null
                        : event.getPaymentId()
        );

        notificationService
                .processFailed(event);
    }
}