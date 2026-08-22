package com.fintech.payment;

import com.fintech.payment.entity.OutboxEvent;
import com.fintech.payment.repository.OutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
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
                "outbox.publisher.enabled=false"
        }
)
class OutboxRepositoryIntegrationTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID testEventId;

    @AfterEach
    void cleanup() {

        if (testEventId != null) {

            outboxRepository.deleteById(
                    testEventId
            );
        }
    }

    @Test
    void skipLockedPreventsTwoWorkersFromClaimingSameRow()
            throws Exception {

        OutboxEvent event =
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .aggregateType("PAYMENT")
                        .aggregateId(
                                UUID.randomUUID()
                        )
                        .eventType(
                                "PAYMENT_CREATED"
                        )
                        .payload("{}")
                        .createdAt(
                                LocalDateTime.now()
                        )
                        .published(false)
                        .attemptCount(0)
                        .build();

        testEventId =
                event.getId();

        outboxRepository.saveAndFlush(
                event
        );

        CountDownLatch firstHasLock =
                new CountDownLatch(1);

        CountDownLatch releaseFirst =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {

            Future<List<OutboxEvent>> first =
                    executor.submit(
                            () ->
                                    transactionTemplate
                                            .execute(
                                                    status -> {

                                                        List<OutboxEvent>
                                                                claimed =
                                                                outboxRepository
                                                                        .findPendingForUpdate(
                                                                                1
                                                                        );

                                                        firstHasLock
                                                                .countDown();

                                                        try {

                                                            if (
                                                                    !releaseFirst
                                                                            .await(
                                                                                    5,
                                                                                    TimeUnit.SECONDS
                                                                            )
                                                            ) {

                                                                throw new IllegalStateException(
                                                                        "Timed out waiting to release lock"
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

            Future<List<OutboxEvent>> second =
                    executor.submit(
                            () ->
                                    transactionTemplate
                                            .execute(
                                                    status ->
                                                            outboxRepository
                                                                    .findPendingForUpdate(
                                                                            1
                                                                    )
                                            )
                    );

            List<OutboxEvent> secondClaim =
                    second.get(
                            5,
                            TimeUnit.SECONDS
                    );

            assertTrue(
                    secondClaim.isEmpty()
            );

            releaseFirst.countDown();

            List<OutboxEvent> firstClaim =
                    first.get(
                            5,
                            TimeUnit.SECONDS
                    );

            assertEquals(
                    1,
                    firstClaim.size()
            );

            assertEquals(
                    testEventId,
                    firstClaim.get(0)
                            .getId()
            );

        } finally {

            releaseFirst.countDown();

            executor.shutdownNow();
        }
    }
}