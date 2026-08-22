package com.fintech.payment.consumer;

import com.fintech.payment.event.LedgerCompletedEvent;
import com.fintech.payment.event.LedgerFailedEvent;
import com.fintech.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class LedgerEventConsumer {

    private final PaymentService paymentService;

    @KafkaListener(
            topics = "${kafka.topics.ledger-completed:ledger.completed}",
            properties = {
                    "spring.json.value.default.type=com.fintech.payment.event.LedgerCompletedEvent"
            }
    )
    public void handleCompleted(
            LedgerCompletedEvent event
    ) {

        log.info(
                "Ledger result received paymentId={} ledgerResult=COMPLETED ledgerTransactionId={}",
                event == null
                        ? null
                        : event.getPaymentId(),
                event == null
                        ? null
                        : event.getLedgerTransactionId()
        );

        paymentService
                .handleLedgerCompleted(
                        event
                );
    }

    @KafkaListener(
            topics = "${kafka.topics.ledger-failed:ledger.failed}",
            properties = {
                    "spring.json.value.default.type=com.fintech.payment.event.LedgerFailedEvent"
            }
    )
    public void handleFailed(
            LedgerFailedEvent event
    ) {

        log.info(
                "Ledger result received paymentId={} ledgerResult=FAILED reason={}",
                event == null
                        ? null
                        : event.getPaymentId(),
                event == null
                        ? null
                        : event.getReason()
        );

        paymentService
                .handleLedgerFailed(
                        event
                );
    }
}