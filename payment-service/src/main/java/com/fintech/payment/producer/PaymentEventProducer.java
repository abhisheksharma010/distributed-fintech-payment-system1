package com.fintech.payment.producer;

import com.fintech.payment.event.PaymentCompletedEvent;
import com.fintech.payment.event.PaymentFailedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentEventProducer {

    private final KafkaTemplate<String, Object>
            kafkaTemplate;

    @Value(
            "${kafka.topics.payments-completed:payments.completed}"
    )
    private String paymentsCompletedTopic;

    @Value(
            "${kafka.topics.payments-failed:payments.failed}"
    )
    private String paymentsFailedTopic;

    public void sendPaymentCompleted(
            PaymentCompletedEvent event
    ) {

        kafkaTemplate.send(
                paymentsCompletedTopic,
                event.getPaymentId()
                        .toString(),
                event
        );
    }

    public void sendPaymentFailed(
            PaymentFailedEvent event
    ) {

        kafkaTemplate.send(
                paymentsFailedTopic,
                event.getPaymentId()
                        .toString(),
                event
        );
    }
}