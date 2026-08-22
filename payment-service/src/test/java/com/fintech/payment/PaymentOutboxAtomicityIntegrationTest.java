package com.fintech.payment;

import com.fintech.payment.dto.CreatePaymentRequest;
import com.fintech.payment.entity.OutboxEvent;
import com.fintech.payment.entity.Payment;
import com.fintech.payment.repository.OutboxRepository;
import com.fintech.payment.repository.PaymentRepository;
import com.fintech.payment.service.PaymentCreationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        properties = {
                "spring.kafka.listener.auto-startup=false",
                "outbox.publisher.enabled=false"
        }
)
class PaymentOutboxAtomicityIntegrationTest {

    @Autowired
    private PaymentCreationService
            paymentCreationService;

    @Autowired
    private PaymentRepository
            paymentRepository;

    @Autowired
    private OutboxRepository
            outboxRepository;

    private UUID paymentId;

    @AfterEach
    void cleanup() {

        if (paymentId != null) {

            outboxRepository.deleteAll(
                    outboxRepository
                            .findByAggregateId(
                                    paymentId
                            )
            );

            paymentRepository.deleteById(
                    paymentId
            );
        }
    }

    @Test
    void committedPaymentHasCorrespondingUnpublishedOutboxEvent() {

        CreatePaymentRequest request =
                validRequest();

        Payment payment =
                paymentCreationService
                        .createNewPayment(
                                request
                        );

        paymentId =
                payment.getId();

        assertTrue(
                paymentRepository
                        .findById(paymentId)
                        .isPresent()
        );

        List<OutboxEvent> events =
                outboxRepository
                        .findByAggregateId(
                                paymentId
                        );

        assertEquals(
                1,
                events.size()
        );

        OutboxEvent event =
                events.get(0);

        assertEquals(
                paymentId,
                event.getAggregateId()
        );

        assertEquals(
                "PAYMENT_CREATED",
                event.getEventType()
        );

        assertFalse(
                event.isPublished()
        );

        assertNull(
                event.getPublishedAt()
        );

        assertEquals(
                0,
                event.getAttemptCount()
        );

        assertNull(
                event.getLastAttemptAt()
        );

        assertNull(
                event.getLastError()
        );
    }

    private static CreatePaymentRequest
    validRequest() {

        CreatePaymentRequest request =
                new CreatePaymentRequest();

        request.setSenderId(
                UUID.randomUUID()
        );

        request.setReceiverId(
                UUID.randomUUID()
        );

        request.setAmount(
                new BigDecimal(
                        "2000.00"
                )
        );

        request.setCurrency("INR");

        request.setIdempotencyKey(
                "story5-atomicity-"
                        + UUID.randomUUID()
        );

        return request;
    }
}