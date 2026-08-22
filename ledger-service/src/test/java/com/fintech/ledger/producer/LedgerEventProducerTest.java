package com.fintech.ledger.producer;

import com.fintech.ledger.config.LedgerResultPublisherProperties;
import com.fintech.ledger.event.LedgerCompletedEvent;
import com.fintech.ledger.event.LedgerFailedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerEventProducerTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private LedgerResultPublishMetrics metrics;

    private LedgerResultPublisherProperties properties;

    private LedgerEventProducer producer;

    @BeforeEach
    void setUp() {

        properties =
                new LedgerResultPublisherProperties();

        properties.setCompletedTopic(
                "ledger.completed"
        );

        properties.setFailedTopic(
                "ledger.failed"
        );

        properties.setMaxAttempts(3);
        properties.setBackoffMs(0);
        properties.setSendTimeoutMs(1000);

        producer =
                new LedgerEventProducer(
                        kafkaTemplate,
                        properties,
                        metrics
                );
    }

    @Test
    void completedEventPublishesSuccessfullyOnFirstAttempt() {

        UUID paymentId =
                UUID.randomUUID();

        LedgerCompletedEvent event =
                LedgerCompletedEvent.builder()
                        .paymentId(paymentId)
                        .ledgerTransactionId(
                                UUID.randomUUID()
                        )
                        .completedAt(
                                Instant.now()
                        )
                        .build();

        when(
                kafkaTemplate.send(
                        eq("ledger.completed"),
                        eq(paymentId.toString()),
                        eq(event)
                )
        ).thenReturn(
                CompletableFuture.completedFuture(
                        null
                )
        );

        assertDoesNotThrow(
                () ->
                        producer
                                .sendLedgerCompleted(
                                        event
                                )
        );

        verify(
                kafkaTemplate,
                times(1)
        ).send(
                eq("ledger.completed"),
                eq(paymentId.toString()),
                eq(event)
        );

        verify(metrics)
                .recordSuccess();

        verify(
                metrics,
                never()
        ).recordRetry();

        verify(
                metrics,
                never()
        ).recordFailure();
    }

    @Test
    void completedEventRetriesUntilSuccess() {

        UUID paymentId =
                UUID.randomUUID();

        LedgerCompletedEvent event =
                LedgerCompletedEvent.builder()
                        .paymentId(paymentId)
                        .ledgerTransactionId(
                                UUID.randomUUID()
                        )
                        .completedAt(
                                Instant.now()
                        )
                        .build();

        CompletableFuture<Object> failure1 =
                new CompletableFuture<>();

        failure1.completeExceptionally(
                new RuntimeException(
                        "temporary Kafka failure 1"
                )
        );

        CompletableFuture<Object> failure2 =
                new CompletableFuture<>();

        failure2.completeExceptionally(
                new RuntimeException(
                        "temporary Kafka failure 2"
                )
        );

        when(
                kafkaTemplate.send(
                        eq("ledger.completed"),
                        eq(paymentId.toString()),
                        eq(event)
                )
        )
                .thenReturn(
                        (CompletableFuture) failure1
                )
                .thenReturn(
                        (CompletableFuture) failure2
                )
                .thenReturn(
                        CompletableFuture
                                .completedFuture(null)
                );

        assertDoesNotThrow(
                () ->
                        producer
                                .sendLedgerCompleted(
                                        event
                                )
        );

        verify(
                kafkaTemplate,
                times(3)
        ).send(
                eq("ledger.completed"),
                eq(paymentId.toString()),
                eq(event)
        );

        verify(
                metrics,
                times(2)
        ).recordRetry();

        verify(metrics)
                .recordSuccess();

        verify(
                metrics,
                never()
        ).recordFailure();
    }

    @Test
    void completedEventThrowsAfterRetryExhaustion() {

        UUID paymentId =
                UUID.randomUUID();

        LedgerCompletedEvent event =
                LedgerCompletedEvent.builder()
                        .paymentId(paymentId)
                        .ledgerTransactionId(
                                UUID.randomUUID()
                        )
                        .completedAt(
                                Instant.now()
                        )
                        .build();

        CompletableFuture<Object> failure =
                new CompletableFuture<>();

        failure.completeExceptionally(
                new RuntimeException(
                        "Kafka unavailable"
                )
        );

        when(
                kafkaTemplate.send(
                        eq("ledger.completed"),
                        eq(paymentId.toString()),
                        eq(event)
                )
        ).thenReturn(
                (CompletableFuture) failure
        );

        assertThrows(
                IllegalStateException.class,
                () ->
                        producer
                                .sendLedgerCompleted(
                                        event
                                )
        );

        verify(
                kafkaTemplate,
                times(3)
        ).send(
                eq("ledger.completed"),
                eq(paymentId.toString()),
                eq(event)
        );

        verify(
                metrics,
                times(2)
        ).recordRetry();

        verify(metrics)
                .recordFailure();

        verify(
                metrics,
                never()
        ).recordSuccess();
    }

    @Test
    void failedEventPreservesBusinessReasonAcrossRetry() {

        UUID paymentId =
                UUID.randomUUID();

        LedgerFailedEvent event =
                LedgerFailedEvent.builder()
                        .paymentId(paymentId)
                        .reason(
                                "INSUFFICIENT_BALANCE"
                        )
                        .failedAt(
                                Instant.now()
                        )
                        .build();

        CompletableFuture<Object> failure =
                new CompletableFuture<>();

        failure.completeExceptionally(
                new RuntimeException(
                        "broker timeout"
                )
        );

        when(
                kafkaTemplate.send(
                        eq("ledger.failed"),
                        eq(paymentId.toString()),
                        eq(event)
                )
        )
                .thenReturn(
                        (CompletableFuture) failure
                )
                .thenReturn(
                        CompletableFuture
                                .completedFuture(null)
                );

        producer.sendLedgerFailed(
                event
        );

        verify(
                kafkaTemplate,
                times(2)
        ).send(
                eq("ledger.failed"),
                eq(paymentId.toString()),
                argThat(
                        value ->
                                value
                                        instanceof
                                        LedgerFailedEvent
                                        &&
                                        "INSUFFICIENT_BALANCE"
                                                .equals(
                                                        ((LedgerFailedEvent)
                                                                value)
                                                                .getReason()
                                                )
                )
        );

        verify(metrics)
                .recordRetry();

        verify(metrics)
                .recordSuccess();
    }
}