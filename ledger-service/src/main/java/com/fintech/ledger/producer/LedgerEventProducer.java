package com.fintech.ledger.producer;

import com.fintech.ledger.config.LedgerResultPublisherProperties;
import com.fintech.ledger.event.LedgerCompletedEvent;
import com.fintech.ledger.event.LedgerFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
@Slf4j
public class LedgerEventProducer {

    private final KafkaTemplate<String, Object>
            kafkaTemplate;

    private final LedgerResultPublisherProperties
            properties;

    private final LedgerResultPublishMetrics
            metrics;

    public void sendLedgerCompleted(
            LedgerCompletedEvent event
    ) {

        sendWithRetry(
                properties.getCompletedTopic(),
                "LEDGER_COMPLETED",
                event.getPaymentId(),
                event
        );
    }

    public void sendLedgerFailed(
            LedgerFailedEvent event
    ) {

        sendWithRetry(
                properties.getFailedTopic(),
                "LEDGER_FAILED",
                event.getPaymentId(),
                event
        );
    }

    private void sendWithRetry(
            String topic,
            String eventType,
            UUID paymentId,
            Object event
    ) {

        String key =
                paymentId == null
                        ? "UNKNOWN"
                        : paymentId.toString();

        int maxAttempts =
                properties.getMaxAttempts();

        for (
                int attempt = 1;
                attempt <= maxAttempts;
                attempt++
        ) {

            try {

                kafkaTemplate
                        .send(
                                topic,
                                key,
                                event
                        )
                        .get(
                                properties
                                        .getSendTimeoutMs(),
                                TimeUnit.MILLISECONDS
                        );

                metrics.recordSuccess();

                log.info(
                        "Ledger result published paymentId={} eventType={} attemptCount={}",
                        paymentId,
                        eventType,
                        attempt
                );

                return;

            } catch (
                    InterruptedException ex
            ) {

                Thread.currentThread()
                        .interrupt();

                metrics.recordFailure();

                log.error(
                        "Ledger result publication interrupted paymentId={} eventType={} attemptCount={}",
                        paymentId,
                        eventType,
                        attempt,
                        ex
                );

                throw new IllegalStateException(
                        "Interrupted while publishing Ledger result",
                        ex
                );

            } catch (
                    ExecutionException
                    | TimeoutException
                    | RuntimeException ex
            ) {

                if (attempt >= maxAttempts) {

                    metrics.recordFailure();

                    log.error(
                            "Ledger result publication exhausted paymentId={} eventType={} attemptCount={} failure={}",
                            paymentId,
                            eventType,
                            attempt,
                            ex.getMessage(),
                            ex
                    );

                    throw new IllegalStateException(
                            "Ledger result publication exhausted paymentId="
                                    + paymentId
                                    + " eventType="
                                    + eventType,
                            ex
                    );
                }

                metrics.recordRetry();

                log.warn(
                        "Ledger result publication retry paymentId={} eventType={} attemptCount={} failure={}",
                        paymentId,
                        eventType,
                        attempt,
                        ex.getMessage()
                );

                sleepBeforeRetry(
                        paymentId,
                        eventType,
                        attempt
                );
            }
        }
    }

    private void sleepBeforeRetry(
            UUID paymentId,
            String eventType,
            int attempt
    ) {

        if (
                properties.getBackoffMs()
                        == 0
        ) {
            return;
        }

        try {

            Thread.sleep(
                    properties.getBackoffMs()
            );

        } catch (
                InterruptedException ex
        ) {

            Thread.currentThread()
                    .interrupt();

            metrics.recordFailure();

            log.error(
                    "Ledger result retry backoff interrupted paymentId={} eventType={} attemptCount={}",
                    paymentId,
                    eventType,
                    attempt,
                    ex
            );

            throw new IllegalStateException(
                    "Interrupted during Ledger result retry backoff",
                    ex
            );
        }
    }
}