package com.fintech.ledger.consumer;

import com.fintech.ledger.entity.FailedEvent;
import com.fintech.ledger.event.PaymentCreatedEvent;
import com.fintech.ledger.repository.FailedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class DLQConsumer {

    private final FailedEventRepository failedEventRepository;
    private final MeterRegistry meterRegistry;

    @KafkaListener(
            topics = "${kafka.topics.payments-created-dlt:payments.created.DLT}",
            groupId = "ledger-dlt-group",
            properties = {
                    "spring.json.value.default.type=com.fintech.ledger.event.PaymentCreatedEvent"
            }
    )
    public void consumeDLT(
            PaymentCreatedEvent event,
            @Header(KafkaHeaders.DLT_EXCEPTION_MESSAGE) String exceptionMessage
    ) {

        log.error("DLQ MESSAGE RECEIVED: {}", event);
        log.error("Failure reason: {}", exceptionMessage);

        FailedEvent failedEvent = FailedEvent.builder()
                .eventType("payments.created")
                .payload(event.toString())
                .failureReason(exceptionMessage)
                .createdAt(LocalDateTime.now())
                .build();

        failedEventRepository.save(failedEvent);

        Counter.builder("ledger.dlq.events")
                .description("Number of events sent to DLQ")
                .register(meterRegistry)
                .increment();
    }
}