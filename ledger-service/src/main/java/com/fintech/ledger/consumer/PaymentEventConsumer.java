package com.fintech.ledger.consumer;

import com.fintech.ledger.event.PaymentCreatedEvent;
import com.fintech.ledger.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final LedgerService ledgerService;

    @KafkaListener(
            topics = "${kafka.topics.payments-created:payments.created}",
            groupId = "${spring.kafka.consumer.group-id:ledger-service-group}",
            properties = {
                    "spring.json.value.default.type=com.fintech.ledger.event.PaymentCreatedEvent"
            }
    )
    public void consume(
            PaymentCreatedEvent event
    ) {

        log.info(
                "Payment event received in Ledger Service paymentId={} amount={}",
                event == null
                        ? null
                        : event.getPaymentId(),
                event == null
                        ? null
                        : event.getAmount()
        );

        ledgerService.processTransfer(event);
    }
}