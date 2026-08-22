package com.fintech.ledger;

import com.fintech.ledger.entity.Account;
import com.fintech.ledger.entity.AccountStatus;
import com.fintech.ledger.entity.LedgerProcessingResult;
import com.fintech.ledger.event.PaymentCreatedEvent;
import com.fintech.ledger.producer.LedgerEventProducer;
import com.fintech.ledger.repository.AccountRepository;
import com.fintech.ledger.repository.LedgerProcessingResultRepository;
import com.fintech.ledger.repository.LedgerRepository;
import com.fintech.ledger.service.LedgerService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(
        properties = {
                "spring.kafka.listener.auto-startup=false",
                "ledger.reconciliation.enabled=false"
        }
)
class LedgerTransactionRollbackIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @SpyBean
    private LedgerProcessingResultRepository
            processingResultRepository;

    @Autowired
    private LedgerEventProducer ledgerEventProducer;

    private UUID senderAccountId;
    private UUID receiverAccountId;
    private UUID paymentId;

    @AfterEach
    void cleanup() {

        if (paymentId != null) {

            ledgerRepository
                    .findByPaymentId(paymentId)
                    .forEach(
                            ledgerRepository::delete
                    );

            processingResultRepository
                    .findById(paymentId)
                    .ifPresent(
                            processingResultRepository::delete
                    );
        }

        if (senderAccountId != null) {

            accountRepository
                    .findById(senderAccountId)
                    .ifPresent(
                            accountRepository::delete
                    );
        }

        if (receiverAccountId != null) {

            accountRepository
                    .findById(receiverAccountId)
                    .ifPresent(
                            accountRepository::delete
                    );
        }
    }

    @Test
    void infrastructureFailureRollsBackBalancesAndLedgerEntries() {

        UUID senderUserId =
                UUID.randomUUID();

        UUID receiverUserId =
                UUID.randomUUID();

        Account sender =
                createAccount(
                        senderUserId,
                        "10000.00"
                );

        Account receiver =
                createAccount(
                        receiverUserId,
                        "5000.00"
                );

        senderAccountId =
                sender.getId();

        receiverAccountId =
                receiver.getId();

        paymentId =
                UUID.randomUUID();

        PaymentCreatedEvent event =
                new PaymentCreatedEvent();

        event.setPaymentId(
                paymentId
        );

        event.setSenderId(
                senderUserId
        );

        event.setReceiverId(
                receiverUserId
        );

        event.setAmount(
                new BigDecimal(
                        "2000.00"
                )
        );

        event.setCurrency(
                "INR"
        );

        event.setIdempotencyKey(
                "story14-rollback-"
                        + UUID.randomUUID()
        );

        /*
         * At this point in LedgerService:
         *
         * 1. sender balance has been reduced
         * 2. receiver balance has been increased
         * 3. both accounts have been saved
         * 4. DEBIT + CREDIT have been saved/flushed
         *
         * Then saveProcessedResult() runs.
         *
         * Forcing this save to fail proves that the
         * surrounding @Transactional boundary rolls
         * EVERYTHING back.
         */
        doThrow(
                new RuntimeException(
                        "Simulated database failure"
                )
        )
                .when(
                        processingResultRepository
                )
                .save(
                        any(
                                LedgerProcessingResult.class
                        )
                );

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                ledgerService
                                        .processTransfer(
                                                event
                                        )
                );

        assertEquals(
                "Simulated database failure",
                exception.getMessage()
        );

        /*
         * Re-read authoritative PostgreSQL state.
         */
        Account senderAfter =
                accountRepository
                        .findById(
                                senderAccountId
                        )
                        .orElseThrow();

        Account receiverAfter =
                accountRepository
                        .findById(
                                receiverAccountId
                        )
                        .orElseThrow();

        /*
         * Sender debit MUST be rolled back.
         */
        assertMoneyEquals(
                "10000.00",
                senderAfter.getBalance()
        );

        /*
         * Receiver credit MUST be rolled back.
         */
        assertMoneyEquals(
                "5000.00",
                receiverAfter.getBalance()
        );

        /*
         * Neither DEBIT nor CREDIT may remain.
         */
        assertTrue(
                ledgerRepository
                        .findByPaymentId(
                                paymentId
                        )
                        .isEmpty()
        );

        /*
         * Processing result itself must also not exist.
         */
        assertTrue(
                processingResultRepository
                        .findById(
                                paymentId
                        )
                        .isEmpty()
        );
    }

    private Account createAccount(
            UUID ownerId,
            String balance
    ) {

        BigDecimal amount =
                new BigDecimal(
                        balance
                );

        Account account =
                Account.builder()
                        .id(
                                UUID.randomUUID()
                        )
                        .ownerId(
                                ownerId
                        )
                        .accountType(
                                "WALLET"
                        )
                        .balance(
                                amount
                        )
                        .openingBalance(
                                amount
                        )
                        .currency(
                                "INR"
                        )
                        .status(
                                AccountStatus.ACTIVE
                        )
                        .createdAt(
                                LocalDateTime.now()
                        )
                        .updatedAt(
                                LocalDateTime.now()
                        )
                        .build();

        return accountRepository
                .saveAndFlush(
                        account
                );
    }

    private static void assertMoneyEquals(
            String expected,
            BigDecimal actual
    ) {

        assertEquals(
                0,
                new BigDecimal(
                        expected
                ).compareTo(
                        actual
                )
        );
    }
}