package com.fintech.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.payment.config.OutboxPublisherProperties;
import com.fintech.payment.entity.OutboxEvent;
import com.fintech.payment.entity.PaymentStatus;
import com.fintech.payment.event.PaymentCreatedEvent;
import com.fintech.payment.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, Object>
            kafkaTemplate;

    @Mock
    private OutboxMetrics outboxMetrics;

    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .findAndRegisterModules();

    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void setUp() {

        OutboxPublisherProperties properties =
                new OutboxPublisherProperties();

        properties.setBatchSize(10);
        properties.setSendTimeoutMs(1000);
        properties.setTopic(
                "payments.created"
        );

        outboxPublisher =
                new OutboxPublisher(
                        outboxRepository,
                        kafkaTemplate,
                        objectMapper,
                        properties,
                        outboxMetrics
                );
    }

    @Test
    void marksPublishedOnlyAfterKafkaAcknowledgement()
            throws Exception {

        OutboxEvent event =
                validEvent();

        when(
                outboxRepository
                        .findPendingForUpdate(10)
        ).thenReturn(
                List.of(event)
        );

        when(
                kafkaTemplate.send(
                        eq("payments.created"),
                        eq(
                                event.getAggregateId()
                                        .toString()
                        ),
                        any(
                                PaymentCreatedEvent.class
                        )
                )
        ).thenReturn(
                CompletableFuture
                        .completedFuture(null)
        );

        outboxPublisher.publishEvents();

        assertTrue(
                event.isPublished()
        );

        assertNotNull(
                event.getPublishedAt()
        );

        assertEquals(
                1,
                event.getAttemptCount()
        );

        assertNotNull(
                event.getLastAttemptAt()
        );

        assertNull(
                event.getLastError()
        );

        verify(outboxRepository)
                .save(event);

        verify(outboxMetrics)
                .recordSuccess();

        verify(
                outboxMetrics,
                never()
        ).recordFailure();
    }

    @Test
    void kafkaFailureKeepsEventUnpublishedAndRetryable()
            throws Exception {

        OutboxEvent event =
                validEvent();

        when(
                outboxRepository
                        .findPendingForUpdate(10)
        ).thenReturn(
                List.of(event)
        );

        CompletableFuture<
                SendResult<String, Object>
                > failure =
                new CompletableFuture<>();

        failure.completeExceptionally(
                new RuntimeException(
                        "Kafka unavailable"
                )
        );

        when(
                kafkaTemplate.send(
                        eq("payments.created"),
                        eq(
                                event.getAggregateId()
                                        .toString()
                        ),
                        any(
                                PaymentCreatedEvent.class
                        )
                )
        ).thenReturn(failure);

        outboxPublisher.publishEvents();

        assertFalse(
                event.isPublished()
        );

        assertNull(
                event.getPublishedAt()
        );

        assertEquals(
                1,
                event.getAttemptCount()
        );

        assertNotNull(
                event.getLastAttemptAt()
        );

        assertNotNull(
                event.getLastError()
        );

        assertTrue(
                event.getLastError()
                        .contains(
                                "Kafka unavailable"
                        )
        );

        verify(outboxRepository)
                .save(event);

        verify(outboxMetrics)
                .recordFailure();

        verify(
                outboxMetrics,
                never()
        ).recordSuccess();
    }

    @Test
    void failedEventIsPublishedOnLaterRetry()
            throws Exception {

        OutboxEvent event =
                validEvent();

        when(
                outboxRepository
                        .findPendingForUpdate(10)
        )
                .thenReturn(
                        List.of(event)
                )
                .thenReturn(
                        List.of(event)
                );

        CompletableFuture<
                SendResult<String, Object>
                > failure =
                new CompletableFuture<>();

        failure.completeExceptionally(
                new RuntimeException(
                        "broker timeout"
                )
        );

        when(
                kafkaTemplate.send(
                        eq("payments.created"),
                        eq(
                                event.getAggregateId()
                                        .toString()
                        ),
                        any(
                                PaymentCreatedEvent.class
                        )
                )
        )
                .thenReturn(failure)
                .thenReturn(
                        CompletableFuture
                                .completedFuture(null)
                );

        outboxPublisher.publishEvents();

        assertFalse(
                event.isPublished()
        );

        assertEquals(
                1,
                event.getAttemptCount()
        );

        outboxPublisher.publishEvents();

        assertTrue(
                event.isPublished()
        );

        assertNotNull(
                event.getPublishedAt()
        );

        assertEquals(
                2,
                event.getAttemptCount()
        );

        assertNull(
                event.getLastError()
        );

        verify(
                kafkaTemplate,
                times(2)
        ).send(
                eq("payments.created"),
                eq(
                        event.getAggregateId()
                                .toString()
                ),
                any(
                        PaymentCreatedEvent.class
                )
        );

        verify(outboxMetrics)
                .recordFailure();

        verify(outboxMetrics)
                .recordSuccess();
    }

    @Test
    void poisonPayloadRemainsUnpublished()
            throws Exception {

        OutboxEvent event =
                validEvent();

        event.setPayload(
                "not-json"
        );

        when(
                outboxRepository
                        .findPendingForUpdate(10)
        ).thenReturn(
                List.of(event)
        );

        outboxPublisher.publishEvents();

        assertFalse(
                event.isPublished()
        );

        assertEquals(
                1,
                event.getAttemptCount()
        );

        assertNotNull(
                event.getLastError()
        );

        verifyNoInteractions(
                kafkaTemplate
        );

        verify(outboxRepository)
                .save(event);

        verify(outboxMetrics)
                .recordFailure();
    }

    @Test
    void noPendingRowsDoesNotSendAnything() {

        when(
                outboxRepository
                        .findPendingForUpdate(10)
        ).thenReturn(
                List.of()
        );

        outboxPublisher.publishEvents();

        verifyNoInteractions(
                kafkaTemplate
        );

        verify(
                outboxRepository,
                never()
        ).save(any());
    }

    private OutboxEvent validEvent()
            throws Exception {

        UUID paymentId =
                UUID.randomUUID();

        PaymentCreatedEvent paymentEvent =
                new PaymentCreatedEvent(
                        paymentId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new BigDecimal(
                                "2000.00"
                        ),
                        "INR",
                        "story5-"
                                + UUID.randomUUID(),
                        PaymentStatus.PROCESSING,
                        LocalDateTime.now()
                );

        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("PAYMENT")
                .aggregateId(paymentId)
                .eventType(
                        "PAYMENT_CREATED"
                )
                .payload(
                        objectMapper
                                .writeValueAsString(
                                        paymentEvent
                                )
                )
                .createdAt(
                        LocalDateTime.now()
                )
                .published(false)
                .attemptCount(0)
                .build();
    }
}