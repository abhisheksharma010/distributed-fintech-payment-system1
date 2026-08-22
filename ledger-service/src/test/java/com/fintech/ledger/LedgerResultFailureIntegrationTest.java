package com.fintech.ledger;

import com.fintech.ledger.entity.Account;
import com.fintech.ledger.entity.AccountStatus;
import com.fintech.ledger.entity.LedgerEntry;
import com.fintech.ledger.entity.LedgerEntryType;
import com.fintech.ledger.event.PaymentCreatedEvent;
import com.fintech.ledger.producer.LedgerEventProducer;
import com.fintech.ledger.repository.AccountRepository;
import com.fintech.ledger.repository.LedgerRepository;
import com.fintech.ledger.service.LedgerService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(
        properties = {
                "spring.kafka.listener.auto-startup=false",
                "ledger.reconciliation.enabled=false"
        }
)
class LedgerResultFailureIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @MockBean
    private LedgerEventProducer ledgerEventProducer;

    private UUID senderAccountId;
    private UUID receiverAccountId;
    private UUID paymentId;

    @AfterEach
    void cleanup() {

        if (paymentId != null) {

            List<LedgerEntry> entries =
                    ledgerRepository.findByPaymentId(
                            paymentId
                    );

            ledgerRepository.deleteAll(
                    entries
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
    void ledgerResultPublicationFailureDoesNotRollbackCommittedMoney() {

        UUID senderUserId =
                UUID.randomUUID();

        UUID receiverUserId =
                UUID.randomUUID();

        Account sender =
                persistAccount(
                        senderUserId,
                        "10000.00"
                );

        Account receiver =
                persistAccount(
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
                "story14-ledger-result-"
                        + UUID.randomUUID()
        );

        /*
         * Simulate ledger.completed publication failure.
         *
         * The financial transaction must already be committed
         * before this publication is attempted.
         */
        doThrow(
                new IllegalStateException(
                        "Ledger result publication exhausted"
                )
        )
                .when(
                        ledgerEventProducer
                )
                .sendLedgerCompleted(
                        any()
                );

        /*
         * Depending on the current after-commit implementation,
         * the publication exception may propagate back to this
         * caller.
         *
         * That is acceptable for this test.
         *
         * What matters is that the already committed financial
         * transaction MUST NOT be rolled back.
         */
        try {

            ledgerService.processTransfer(
                    event
            );

        } catch (IllegalStateException ex) {

            assertTrue(
                    ex.getMessage()
                            .contains(
                                    "Ledger result publication exhausted"
                            )
            );
        }

        /*
         * Re-read accounts from PostgreSQL.
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
         * Financial state must remain committed.
         */
        assertMoneyEquals(
                "8000.00",
                senderAfter.getBalance()
        );

        assertMoneyEquals(
                "7000.00",
                receiverAfter.getBalance()
        );

        /*
         * Double-entry accounting must also remain committed.
         */
        List<LedgerEntry> entries =
                ledgerRepository
                        .findByPaymentId(
                                paymentId
                        );

        assertEquals(
                2,
                entries.size()
        );

        long debitCount =
                entries.stream()
                        .filter(
                                entry ->
                                        entry.getEntryType()
                                                ==
                                                LedgerEntryType.DEBIT
                        )
                        .count();

        long creditCount =
                entries.stream()
                        .filter(
                                entry ->
                                        entry.getEntryType()
                                                ==
                                                LedgerEntryType.CREDIT
                        )
                        .count();

        assertEquals(
                1,
                debitCount
        );

        assertEquals(
                1,
                creditCount
        );

        BigDecimal debitTotal =
                entries.stream()
                        .filter(
                                entry ->
                                        entry.getEntryType()
                                                ==
                                                LedgerEntryType.DEBIT
                        )
                        .map(
                                LedgerEntry::getAmount
                        )
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        BigDecimal creditTotal =
                entries.stream()
                        .filter(
                                entry ->
                                        entry.getEntryType()
                                                ==
                                                LedgerEntryType.CREDIT
                        )
                        .map(
                                LedgerEntry::getAmount
                        )
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        /*
         * Double-entry invariant.
         */
        assertEquals(
                0,
                debitTotal.compareTo(
                        creditTotal
                )
        );

        assertMoneyEquals(
                "2000.00",
                debitTotal
        );
    }

    private Account persistAccount(
            UUID ownerId,
            String balance
    ) {

        BigDecimal initialBalance =
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
                                initialBalance
                        )
                        .openingBalance(
                                initialBalance
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