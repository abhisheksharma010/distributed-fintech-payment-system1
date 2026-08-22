package com.fintech.payment;

import com.fintech.payment.entity.Payment;
import com.fintech.payment.entity.PaymentStatus;
import com.fintech.payment.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        properties = {
                "spring.kafka.listener.auto-startup=false",
                "outbox.publisher.enabled=false",
                "payment.recovery.enabled=false"
        }
)
class PaymentRecoveryRepositoryIntegrationTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final List<UUID> paymentIds =
            new ArrayList<>();

    @AfterEach
    void cleanup() {

        paymentIds.forEach(
                paymentRepository::deleteById
        );

        paymentIds.clear();
    }

    @Test
    void selectsOnlyStaleProcessingPayments() {

        LocalDateTime now =
                LocalDateTime.now();

        Payment stale =
                savePayment(
                        PaymentStatus.PROCESSING,
                        now.minusMinutes(10)
                );

        savePayment(
                PaymentStatus.PROCESSING,
                now.minusMinutes(1)
        );

        savePayment(
                PaymentStatus.COMPLETED,
                now.minusMinutes(20)
        );

        savePayment(
                PaymentStatus.FAILED,
                now.minusMinutes(20)
        );

        List<Payment> result =
                transactionTemplate.execute(
                        status ->
                                paymentRepository
                                        .findStuckPaymentsForRecovery(
                                                now.minusMinutes(5),
                                                now.minusMinutes(1),
                                                20
                                        )
                );

        assertNotNull(result);

        assertEquals(
                1,
                result.size()
        );

        assertEquals(
                stale.getId(),
                result.get(0)
                        .getId()
        );
    }

    @Test
    void recentlyRecoveredPaymentIsNotImmediatelySelectedAgain() {

        LocalDateTime now =
                LocalDateTime.now();

        Payment payment =
                savePayment(
                        PaymentStatus.PROCESSING,
                        now.minusMinutes(10)
                );

        payment.setLastRecoveryAt(
                now.minusSeconds(10)
        );

        paymentRepository.saveAndFlush(
                payment
        );

        List<Payment> result =
                transactionTemplate.execute(
                        status ->
                                paymentRepository
                                        .findStuckPaymentsForRecovery(
                                                now.minusMinutes(5),
                                                now.minusMinutes(1),
                                                20
                                        )
                );

        assertNotNull(result);

        assertTrue(
                result.isEmpty()
        );
    }

    @Test
    void skipLockedAllowsOnlyOneRecoveryWorkerToClaimPayment()
            throws Exception {

        LocalDateTime now =
                LocalDateTime.now();

        Payment payment =
                savePayment(
                        PaymentStatus.PROCESSING,
                        now.minusMinutes(10)
                );

        CountDownLatch firstHasLock =
                new CountDownLatch(1);

        CountDownLatch releaseFirst =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {

            Future<List<Payment>> first =
                    executor.submit(
                            () ->
                                    transactionTemplate
                                            .execute(
                                                    status -> {

                                                        List<Payment>
                                                                claimed =
                                                                paymentRepository
                                                                        .findStuckPaymentsForRecovery(
                                                                                now.minusMinutes(5),
                                                                                now.minusMinutes(1),
                                                                                1
                                                                        );

                                                        firstHasLock
                                                                .countDown();

                                                        try {

                                                            if (
                                                                    !releaseFirst.await(
                                                                            5,
                                                                            TimeUnit.SECONDS
                                                                    )
                                                            ) {

                                                                throw new IllegalStateException(
                                                                        "Timed out waiting to release first worker"
                                                                );
                                                            }

                                                        } catch (
                                                                InterruptedException ex
                                                        ) {

                                                            Thread.currentThread()
                                                                    .interrupt();

                                                            throw new IllegalStateException(
                                                                    ex
                                                            );
                                                        }

                                                        return claimed;
                                                    }
                                            )
                    );

            assertTrue(
                    firstHasLock.await(
                            5,
                            TimeUnit.SECONDS
                    )
            );

            Future<List<Payment>> second =
                    executor.submit(
                            () ->
                                    transactionTemplate
                                            .execute(
                                                    status ->
                                                            paymentRepository
                                                                    .findStuckPaymentsForRecovery(
                                                                            now.minusMinutes(5),
                                                                            now.minusMinutes(1),
                                                                            1
                                                                    )
                                            )
                    );

            List<Payment> secondClaim =
                    second.get(
                            5,
                            TimeUnit.SECONDS
                    );

            assertTrue(
                    secondClaim.isEmpty()
            );

            releaseFirst.countDown();

            List<Payment> firstClaim =
                    first.get(
                            5,
                            TimeUnit.SECONDS
                    );

            assertEquals(
                    1,
                    firstClaim.size()
            );

            assertEquals(
                    payment.getId(),
                    firstClaim.get(0)
                            .getId()
            );

        } finally {

            releaseFirst.countDown();

            executor.shutdownNow();
        }
    }

    private Payment savePayment(
            PaymentStatus status,
            LocalDateTime updatedAt
    ) {

        Payment payment =
                Payment.builder()
                        .id(UUID.randomUUID())
                        .senderId(
                                UUID.randomUUID()
                        )
                        .receiverId(
                                UUID.randomUUID()
                        )
                        .amount(
                                new BigDecimal(
                                        "2000.00"
                                )
                        )
                        .currency("INR")
                        .status(status)
                        .idempotencyKey(
                                "story8-recovery-"
                                        + UUID.randomUUID()
                        )
                        .createdAt(
                                updatedAt
                        )
                        .updatedAt(
                                updatedAt
                        )
                        .recoveryAttemptCount(0)
                        .build();

        Payment saved =
                paymentRepository
                        .saveAndFlush(
                                payment
                        );

        paymentIds.add(
                saved.getId()
        );

        return saved;
    }
}