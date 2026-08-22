package com.fintech.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.payment.config.OutboxPublisherProperties;
import com.fintech.payment.entity.OutboxEvent;
import com.fintech.payment.event.PaymentCreatedEvent;
import com.fintech.payment.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@ConditionalOnProperty(
        name = "outbox.publisher.enabled",
        havingValue = "true",
        matchIfMissing = true
)
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private static final int MAX_ERROR_LENGTH =
            1000;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object>
            kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxPublisherProperties properties;
    private final OutboxMetrics outboxMetrics;

    @Scheduled(
            fixedDelayString =
                    "${outbox.publisher.poll-interval-ms:2000}"
    )
    @Transactional
    public void publishEvents() {

        List<OutboxEvent> events =
                outboxRepository
                        .findPendingForUpdate(
                                properties.getBatchSize()
                        );

        for (OutboxEvent event : events) {
            publishEvent(event);
        }
    }

    private void publishEvent(
            OutboxEvent event
    ) {

        LocalDateTime attemptTime =
                LocalDateTime.now();

        event.setAttemptCount(
                event.getAttemptCount() + 1
        );

        event.setLastAttemptAt(
                attemptTime
        );

        event.setLastError(null);

        log.info(
                "Outbox publish attempt eventId={} aggregateId={} eventType={} attemptCount={}",
                event.getId(),
                event.getAggregateId(),
                event.getEventType(),
                event.getAttemptCount()
        );

        try {

            PaymentCreatedEvent paymentEvent =
                    toPaymentCreatedEvent(
                            event
                    );

            kafkaTemplate
                    .send(
                            properties.getTopic(),
                            event.getAggregateId()
                                    .toString(),
                            paymentEvent
                    )
                    .get(
                            properties
                                    .getSendTimeoutMs(),
                            TimeUnit.MILLISECONDS
                    );

        } catch (InterruptedException ex) {

            Thread.currentThread()
                    .interrupt();

            recordFailure(
                    event,
                    ex
            );

            return;

        } catch (
                ExecutionException
                | TimeoutException
                | RuntimeException ex
        ) {

            recordFailure(
                    event,
                    ex
            );

            return;
        }

        event.setPublished(true);

        event.setPublishedAt(
                LocalDateTime.now()
        );

        event.setLastError(null);

        /*
         * Mark published only after Kafka ACK.
         *
         * If Kafka succeeds but this DB update fails,
         * the transaction rolls back and the row remains
         * unpublished.
         *
         * It will therefore be retried after restart.
         *
         * Stories 3-4 make that duplicate Kafka delivery
         * safe at the business layer.
         */
        outboxRepository.save(
                event
        );

        outboxMetrics.recordSuccess();

        log.info(
                "Outbox publish succeeded eventId={} aggregateId={} eventType={} attemptCount={}",
                event.getId(),
                event.getAggregateId(),
                event.getEventType(),
                event.getAttemptCount()
        );
    }

    private PaymentCreatedEvent
    toPaymentCreatedEvent(
            OutboxEvent event
    ) {

        if (
                !"PAYMENT_CREATED"
                        .equals(
                                event.getEventType()
                        )
        ) {

            throw new IllegalArgumentException(
                    "Unsupported outbox event type: "
                            + event.getEventType()
            );
        }

        if (
                event.getAggregateId() == null
                        || event.getPayload() == null
                        || event.getPayload().isBlank()
        ) {

            throw new IllegalArgumentException(
                    "Missing required outbox event metadata"
            );
        }

        try {

            return objectMapper.readValue(
                    event.getPayload(),
                    PaymentCreatedEvent.class
            );

        } catch (Exception ex) {

            throw new IllegalArgumentException(
                    "Unable to deserialize outbox payload",
                    ex
            );
        }
    }

    private void recordFailure(
            OutboxEvent event,
            Exception exception
    ) {

        event.setPublished(false);

        event.setPublishedAt(null);

        event.setLastError(
                truncateError(
                        exception
                )
        );

        outboxRepository.save(
                event
        );

        outboxMetrics.recordFailure();

        log.error(
                "Outbox publish failed eventId={} aggregateId={} eventType={} attemptCount={} error={}",
                event.getId(),
                event.getAggregateId(),
                event.getEventType(),
                event.getAttemptCount(),
                event.getLastError()
        );
    }

    private String truncateError(
            Exception exception
    ) {

        String message =
                exception.getMessage();

        if (
                message == null
                        || message.isBlank()
        ) {

            message =
                    exception.getClass()
                            .getSimpleName();
        }

        if (
                message.length()
                        <= MAX_ERROR_LENGTH
        ) {

            return message;
        }

        return message.substring(
                0,
                MAX_ERROR_LENGTH
        );
    }
}