package com.fintech.payment;

import com.fintech.payment.dto.CreatePaymentRequest;
import com.fintech.payment.entity.OutboxEvent;
import com.fintech.payment.repository.OutboxRepository;
import com.fintech.payment.repository.PaymentRepository;
import com.fintech.payment.service.PaymentCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(
        properties = {
                "spring.kafka.listener.auto-startup=false",
                "outbox.publisher.enabled=false"
        }
)
class PaymentOutboxRollbackIntegrationTest {

    @Autowired
    private PaymentCreationService
            paymentCreationService;

    @Autowired
    private PaymentRepository
            paymentRepository;

    @MockBean
    private OutboxRepository
            outboxRepository;

    @Test
    void outboxPersistenceFailureRollsBackPayment() {

        CreatePaymentRequest request =
                validRequest();

        when(
                outboxRepository.save(
                        any(
                                OutboxEvent.class
                        )
                )
        ).thenThrow(
                new RuntimeException(
                        "simulated outbox database failure"
                )
        );

        assertThrows(
                RuntimeException.class,
                () ->
                        paymentCreationService
                                .createNewPayment(
                                        request
                                )
        );

        assertTrue(
                paymentRepository
                        .findByIdempotencyKey(
                                request
                                        .getIdempotencyKey()
                        )
                        .isEmpty()
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
                "story5-rollback-"
                        + UUID.randomUUID()
        );

        return request;
    }
}