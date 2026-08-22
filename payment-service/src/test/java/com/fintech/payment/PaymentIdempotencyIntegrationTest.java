package com.fintech.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.payment.dto.CreatePaymentRequest;
import com.fintech.payment.entity.OutboxEvent;
import com.fintech.payment.entity.Payment;
import com.fintech.payment.entity.PaymentStatus;
import com.fintech.payment.repository.OutboxRepository;
import com.fintech.payment.repository.PaymentRepository;
import com.fintech.payment.service.OutboxPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentIdempotencyIntegrationTest {

    private static final String TEST_PREFIX =
            "story4-it-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @MockBean
    private OutboxPublisher outboxPublisher;

    @AfterEach
    void cleanup() {

        List<Payment> testPayments =
                paymentRepository
                        .findAll()
                        .stream()
                        .filter(
                                payment ->
                                        payment.getIdempotencyKey()
                                                != null
                                                &&
                                                payment
                                                        .getIdempotencyKey()
                                                        .startsWith(
                                                                TEST_PREFIX
                                                        )
                        )
                        .toList();

        Set<UUID> paymentIds =
                testPayments
                        .stream()
                        .map(Payment::getId)
                        .collect(
                                java.util.stream.Collectors
                                        .toSet()
                        );

        List<OutboxEvent> outboxEvents =
                outboxRepository
                        .findAll()
                        .stream()
                        .filter(
                                event ->
                                        paymentIds.contains(
                                                event.getAggregateId()
                                        )
                        )
                        .toList();

        outboxRepository.deleteAll(
                outboxEvents
        );

        paymentRepository.deleteAll(
                testPayments
        );
    }

    @Test
    void databaseRejectsDuplicateIdempotencyKey() {

        String key =
                TEST_PREFIX
                        + UUID.randomUUID();

        Payment first =
                payment(
                        key,
                        "2000.00"
                );

        paymentRepository.saveAndFlush(
                first
        );

        Payment second =
                payment(
                        key,
                        "2000.00"
                );

        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        paymentRepository
                                .saveAndFlush(second)
        );

        assertEquals(
                1,
                paymentRepository
                        .countByIdempotencyKey(key)
        );
    }

    @Test
    void tenConcurrentSameRequestsReturnOnePayment()
            throws Exception {

        String key =
                TEST_PREFIX
                        + UUID.randomUUID();

        UUID senderId =
                UUID.randomUUID();

        UUID receiverId =
                UUID.randomUUID();

        CreatePaymentRequest request =
                request(
                        key,
                        senderId,
                        receiverId,
                        "2000.00"
                );

        ExecutorService executor =
                Executors.newFixedThreadPool(10);

        try {

            List<Callable<HttpResult>> tasks =
                    new ArrayList<>();

            for (int i = 0; i < 10; i++) {

                tasks.add(
                        () -> postPayment(request)
                );
            }

            List<Future<HttpResult>> futures =
                    executor.invokeAll(tasks);

            Set<String> paymentIds =
                    new HashSet<>();

            for (
                    Future<HttpResult> future :
                    futures
            ) {

                HttpResult result =
                        future.get();

                assertEquals(
                        200,
                        result.status()
                );

                JsonNode body =
                        objectMapper.readTree(
                                result.body()
                        );

                paymentIds.add(
                        body.get("paymentId")
                                .asText()
                );
            }

            assertEquals(
                    1,
                    paymentIds.size()
            );

            assertEquals(
                    1,
                    paymentRepository
                            .countByIdempotencyKey(
                                    key
                            )
            );

            Payment stored =
                    paymentRepository
                            .findByIdempotencyKey(key)
                            .orElseThrow();

            assertEquals(
                    PaymentStatus.PROCESSING,
                    stored.getStatus()
            );

            assertEquals(
                    senderId,
                    stored.getSenderId()
            );

            assertEquals(
                    receiverId,
                    stored.getReceiverId()
            );

            assertEquals(
                    0,
                    new BigDecimal("2000.00")
                            .compareTo(
                                    stored.getAmount()
                            )
            );

        } finally {

            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDifferentRequestsWithSameKeyCreateOnePaymentAndRejectOther()
            throws Exception {

        String key =
                TEST_PREFIX
                        + UUID.randomUUID();

        UUID senderId =
                UUID.randomUUID();

        UUID receiverId =
                UUID.randomUUID();

        CreatePaymentRequest first =
                request(
                        key,
                        senderId,
                        receiverId,
                        "2000.00"
                );

        CreatePaymentRequest second =
                request(
                        key,
                        senderId,
                        receiverId,
                        "5000.00"
                );

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {

            List<Future<HttpResult>> futures =
                    executor.invokeAll(
                            List.of(
                                    () ->
                                            postPayment(first),
                                    () ->
                                            postPayment(second)
                            )
                    );

            List<HttpResult> results =
                    new ArrayList<>();

            for (
                    Future<HttpResult> future :
                    futures
            ) {

                results.add(
                        future.get()
                );
            }

            long successCount =
                    results.stream()
                            .filter(
                                    result ->
                                            result.status()
                                                    == 200
                            )
                            .count();

            long conflictCount =
                    results.stream()
                            .filter(
                                    result ->
                                            result.status()
                                                    == 409
                            )
                            .count();

            assertEquals(
                    1,
                    successCount
            );

            assertEquals(
                    1,
                    conflictCount
            );

            assertEquals(
                    1,
                    paymentRepository
                            .countByIdempotencyKey(
                                    key
                            )
            );

            HttpResult conflict =
                    results.stream()
                            .filter(
                                    result ->
                                            result.status()
                                                    == 409
                            )
                            .findFirst()
                            .orElseThrow();

            assertEquals(
                    "IDEMPOTENCY_KEY_REUSED",
                    objectMapper
                            .readTree(
                                    conflict.body()
                            )
                            .get("code")
                            .asText()
            );

        } finally {

            executor.shutdownNow();
        }
    }

    private HttpResult postPayment(
            CreatePaymentRequest request
    ) throws Exception {

        var result =
                mockMvc.perform(
                                post(
                                        "/api/payments/pay"
                                )
                                        .header(
                                                "X-USER-ID",
                                                request.getSenderId()
                                                        .toString()
                                        )
                                        .contentType(
                                                MediaType
                                                        .APPLICATION_JSON
                                        )
                                        .content(
                                                objectMapper
                                                        .writeValueAsString(
                                                                request
                                                        )
                                        )
                        )
                        .andReturn();

        return new HttpResult(
                result.getResponse()
                        .getStatus(),
                result.getResponse()
                        .getContentAsString()
        );
    }

    private static CreatePaymentRequest request(
            String idempotencyKey,
            UUID senderId,
            UUID receiverId,
            String amount
    ) {

        CreatePaymentRequest request =
                new CreatePaymentRequest();

        request.setSenderId(senderId);
        request.setReceiverId(receiverId);

        request.setAmount(
                new BigDecimal(amount)
        );

        request.setCurrency("INR");

        request.setIdempotencyKey(
                idempotencyKey
        );

        return request;
    }

    private static Payment payment(
            String idempotencyKey,
            String amount
    ) {

        return Payment.builder()
                .id(UUID.randomUUID())
                .senderId(UUID.randomUUID())
                .receiverId(UUID.randomUUID())
                .amount(
                        new BigDecimal(amount)
                )
                .currency("INR")
                .status(
                        PaymentStatus.PROCESSING
                )
                .idempotencyKey(
                        idempotencyKey
                )
                .createdAt(
                        LocalDateTime.now()
                )
                .updatedAt(
                        LocalDateTime.now()
                )
                .recoveryAttemptCount(0)
                .build();
    }

    private record HttpResult(
            int status,
            String body
    ) {
    }
}