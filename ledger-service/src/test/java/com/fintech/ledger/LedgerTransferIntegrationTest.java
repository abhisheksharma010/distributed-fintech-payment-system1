package com.fintech.ledger;

import com.fintech.ledger.entity.Account;
import com.fintech.ledger.entity.AccountStatus;
import com.fintech.ledger.entity.LedgerEntry;
import com.fintech.ledger.entity.LedgerEntryType;
import com.fintech.ledger.event.LedgerCompletedEvent;
import com.fintech.ledger.event.LedgerFailedEvent;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.fintech.ledger.entity.LedgerProcessingResultType;
import com.fintech.ledger.repository.LedgerProcessingResultRepository;


@SpringBootTest
class LedgerTransferIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerProcessingResultRepository
            processingResultRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockBean
    private LedgerEventProducer ledgerEventProducer;
    @Autowired
    private LedgerRepository ledgerRepository;

    private final List<UUID> testAccountIds =
            new ArrayList<>();

    private final List<UUID> testPaymentIds =
            new ArrayList<>();

    @AfterEach
    void cleanup() {

        testPaymentIds.forEach(
                processingResultRepository::deleteById
        );

        testPaymentIds.clear();

        ledgerRepository.deleteByAccountIdIn(
                testAccountIds
        );

        testAccountIds.forEach(
                accountRepository::deleteById
        );

        testAccountIds.clear();
    }

    @Test
    void successfulTransferUpdatesBothBalancesAtomically() {

        Account sender =
                saveAccount(
                        new BigDecimal("10000.00")
                );

        Account receiver =
                saveAccount(
                        new BigDecimal("5000.00")
                );

        ledgerService.processTransfer(
                event(
                        sender.getOwnerId(),
                        receiver.getOwnerId(),
                        "2000.00"
                )
        );

        Account updatedSender =
                accountRepository
                        .findById(sender.getId())
                        .orElseThrow();

        Account updatedReceiver =
                accountRepository
                        .findById(receiver.getId())
                        .orElseThrow();

        assertEquals(
                new BigDecimal("8000.00"),
                updatedSender.getBalance()
        );

        assertEquals(
                new BigDecimal("7000.00"),
                updatedReceiver.getBalance()
        );

        verify(
                ledgerEventProducer
        ).sendLedgerCompleted(
                any(LedgerCompletedEvent.class)
        );
    }

    @Test
    void insufficientBalanceLeavesBothBalancesUnchanged() {

        Account sender =
                saveAccount(
                        new BigDecimal("1999.00")
                );

        Account receiver =
                saveAccount(
                        new BigDecimal("5000.00")
                );

        PaymentCreatedEvent payment =
                event(
                        sender.getOwnerId(),
                        receiver.getOwnerId(),
                        "2000.00"
                );

        ledgerService.processTransfer(payment);

        assertEquals(
                new BigDecimal("1999.00"),
                accountRepository
                        .findById(sender.getId())
                        .orElseThrow()
                        .getBalance()
        );

        assertEquals(
                new BigDecimal("5000.00"),
                accountRepository
                        .findById(receiver.getId())
                        .orElseThrow()
                        .getBalance()
        );

        var processingResult =
                processingResultRepository
                        .findById(
                                payment.getPaymentId()
                        )
                        .orElseThrow();

        assertEquals(
                LedgerProcessingResultType.FAILED,
                processingResult.getResultType()
        );

        assertEquals(
                "INSUFFICIENT_BALANCE",
                processingResult.getFailureReason()
        );

        verify(
                ledgerEventProducer
        ).sendLedgerFailed(
                any(LedgerFailedEvent.class)
        );
    }

    @Test
    void databaseFailureDuringCreditRollsBackSenderDebit() {

        Account sender =
                saveAccount(
                        new BigDecimal("100.00")
                );

        Account receiver =
                saveAccount(
                        new BigDecimal("999999999999.99")
                );

        assertThrows(
                RuntimeException.class,
                () ->
                        ledgerService.processTransfer(
                                event(
                                        sender.getOwnerId(),
                                        receiver.getOwnerId(),
                                        "1.00"
                                )
                        )
        );

        assertEquals(
                new BigDecimal("100.00"),
                accountRepository
                        .findById(sender.getId())
                        .orElseThrow()
                        .getBalance()
        );

        assertEquals(
                new BigDecimal("999999999999.99"),
                accountRepository
                        .findById(receiver.getId())
                        .orElseThrow()
                        .getBalance()
        );

        verify(
                ledgerEventProducer,
                never()
        ).sendLedgerCompleted(
                any(LedgerCompletedEvent.class)
        );
    }

    @Test
    void concurrentTransfersCannotDoubleSpendSender()
            throws Exception {

        Account sender =
                saveAccount(
                        new BigDecimal("1000.00")
                );

        Account receiverOne =
                saveAccount(
                        new BigDecimal("0.00")
                );

        Account receiverTwo =
                saveAccount(
                        new BigDecimal("0.00")
                );

        PaymentCreatedEvent first =
                event(
                        sender.getOwnerId(),
                        receiverOne.getOwnerId(),
                        "800.00"
                );

        PaymentCreatedEvent second =
                event(
                        sender.getOwnerId(),
                        receiverTwo.getOwnerId(),
                        "800.00"
                );

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {

            List<Callable<Void>> tasks =
                    List.of(
                            () -> {
                                ledgerService
                                        .processTransfer(first);
                                return null;
                            },
                            () -> {
                                ledgerService
                                        .processTransfer(second);
                                return null;
                            }
                    );

            List<Future<Void>> results =
                    executor.invokeAll(tasks);

            for (Future<Void> result : results) {
                result.get();
            }


            assertEquals(
                    new BigDecimal("200.00"),
                    accountRepository
                            .findById(sender.getId())
                            .orElseThrow()
                            .getBalance()
            );

            BigDecimal receiverTotal =
                    accountRepository
                            .findById(receiverOne.getId())
                            .orElseThrow()
                            .getBalance()
                            .add(
                                    accountRepository
                                            .findById(
                                                    receiverTwo.getId()
                                            )
                                            .orElseThrow()
                                            .getBalance()
                            );

            assertEquals(
                    new BigDecimal("800.00"),
                    receiverTotal
            );

        } finally {
            executor.shutdownNow();
        }
    }

    private Account saveAccount(
            BigDecimal balance
    ) {

        UUID ownerId =
                UUID.randomUUID();

        return transactionTemplate.execute(
                status -> {

                    Account account =
                            Account.builder()
                                    .id(UUID.randomUUID())
                                    .ownerId(ownerId)
                                    .accountType("WALLET")
                                    .balance(balance)
                                    .currency("INR")
                                    .status(AccountStatus.ACTIVE)
                                    .build();

                    Account saved =
                            accountRepository
                                    .saveAndFlush(account);

                    testAccountIds.add(
                            saved.getId()
                    );

                    return saved;
                }
        );
    }

    private PaymentCreatedEvent event(
            UUID senderId,
            UUID receiverId,
            String amount
    ) {

        UUID paymentId =
                UUID.randomUUID();

        testPaymentIds.add(
                paymentId
        );

        return new PaymentCreatedEvent(
                paymentId,
                senderId,
                receiverId,
                new BigDecimal(amount),
                "INR",
                "story2-integration-"
                        + UUID.randomUUID(),
                "PROCESSING",
                null
        );
    }
    @Test
    void successfulTransferCreatesBalancedDoubleEntry() {

        Account sender =
                saveAccount(
                        new BigDecimal("10000.00")
                );

        Account receiver =
                saveAccount(
                        new BigDecimal("5000.00")
                );

        PaymentCreatedEvent payment =
                event(
                        sender.getOwnerId(),
                        receiver.getOwnerId(),
                        "2000.00"
                );

        ledgerService.processTransfer(payment);

        Account updatedSender =
                accountRepository
                        .findById(sender.getId())
                        .orElseThrow();

        Account updatedReceiver =
                accountRepository
                        .findById(receiver.getId())
                        .orElseThrow();

        assertEquals(
                new BigDecimal("8000.00"),
                updatedSender.getBalance()
        );

        assertEquals(
                new BigDecimal("7000.00"),
                updatedReceiver.getBalance()
        );

        List<LedgerEntry> entries =
                ledgerRepository.findByPaymentId(
                        payment.getPaymentId()
                );

        assertEquals(
                2,
                entries.size()
        );

        assertEquals(
                1,
                entries.stream()
                        .filter(
                                e ->
                                        e.getEntryType()
                                                == LedgerEntryType.DEBIT
                        )
                        .count()
        );

        assertEquals(
                1,
                entries.stream()
                        .filter(
                                e ->
                                        e.getEntryType()
                                                == LedgerEntryType.CREDIT
                        )
                        .count()
        );

        LedgerEntry debit =
                entries.stream()
                        .filter(
                                e ->
                                        e.getEntryType()
                                                == LedgerEntryType.DEBIT
                        )
                        .findFirst()
                        .orElseThrow();

        LedgerEntry credit =
                entries.stream()
                        .filter(
                                e ->
                                        e.getEntryType()
                                                == LedgerEntryType.CREDIT
                        )
                        .findFirst()
                        .orElseThrow();

        assertEquals(
                sender.getId(),
                debit.getAccountId()
        );

        assertEquals(
                receiver.getId(),
                credit.getAccountId()
        );

        assertEquals(
                payment.getPaymentId(),
                debit.getPaymentId()
        );

        assertEquals(
                payment.getPaymentId(),
                credit.getPaymentId()
        );

        assertEquals(
                new BigDecimal("2000.00"),
                debit.getAmount()
        );

        assertEquals(
                new BigDecimal("2000.00"),
                credit.getAmount()
        );

        BigDecimal totalDebit =
                entries.stream()
                        .filter(
                                e ->
                                        e.getEntryType()
                                                == LedgerEntryType.DEBIT
                        )
                        .map(LedgerEntry::getAmount)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        BigDecimal totalCredit =
                entries.stream()
                        .filter(
                                e ->
                                        e.getEntryType()
                                                == LedgerEntryType.CREDIT
                        )
                        .map(LedgerEntry::getAmount)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        assertEquals(
                totalDebit,
                totalCredit
        );

        assertEquals(
                LedgerProcessingResultType.PROCESSED,
                processingResultRepository
                        .findById(
                                payment.getPaymentId()
                        )
                        .orElseThrow()
                        .getResultType()
        );

        verify(
                ledgerEventProducer
        ).sendLedgerCompleted(
                any(LedgerCompletedEvent.class)
        );
    }
    @Test
    void duplicatePaymentEventCreatesOnlyOneDebitAndOneCredit() {

        Account sender =
                saveAccount(
                        new BigDecimal("1000.00")
                );

        Account receiver =
                saveAccount(
                        new BigDecimal("0.00")
                );

        PaymentCreatedEvent payment =
                event(
                        sender.getOwnerId(),
                        receiver.getOwnerId(),
                        "800.00"
                );

        ledgerService.processTransfer(payment);

        clearInvocations(
                ledgerEventProducer
        );

        ledgerService.processTransfer(payment);

        assertEquals(
                new BigDecimal("200.00"),
                accountRepository
                        .findById(sender.getId())
                        .orElseThrow()
                        .getBalance()
        );

        assertEquals(
                new BigDecimal("800.00"),
                accountRepository
                        .findById(receiver.getId())
                        .orElseThrow()
                        .getBalance()
        );

        assertEquals(
                2,
                ledgerRepository
                        .findByPaymentId(
                                payment.getPaymentId()
                        )
                        .size()
        );

        verify(
                ledgerEventProducer,
                times(1)
        ).sendLedgerCompleted(
                any(LedgerCompletedEvent.class)
        );
    }
    @Test
    void concurrentDuplicatePaymentEventCreatesOnlyOneLedgerPair()
            throws Exception {

        Account sender =
                saveAccount(
                        new BigDecimal("1000.00")
                );

        Account receiver =
                saveAccount(
                        new BigDecimal("0.00")
                );

        PaymentCreatedEvent payment =
                event(
                        sender.getOwnerId(),
                        receiver.getOwnerId(),
                        "800.00"
                );

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {

            List<Callable<Void>> tasks =
                    List.of(
                            () -> {
                                ledgerService
                                        .processTransfer(payment);
                                return null;
                            },
                            () -> {
                                ledgerService
                                        .processTransfer(payment);
                                return null;
                            }
                    );

            List<Future<Void>> results =
                    executor.invokeAll(tasks);

            for (Future<Void> result : results) {
                result.get();
            }

            assertEquals(
                    new BigDecimal("200.00"),
                    accountRepository
                            .findById(sender.getId())
                            .orElseThrow()
                            .getBalance()
            );

            assertEquals(
                    new BigDecimal("800.00"),
                    accountRepository
                            .findById(receiver.getId())
                            .orElseThrow()
                            .getBalance()
            );

            assertEquals(
                    2,
                    ledgerRepository
                            .findByPaymentId(
                                    payment.getPaymentId()
                            )
                            .size()
            );

        } finally {

            executor.shutdownNow();
        }
    }

    @Test
    void kafkaResultFailureDoesNotRollbackCommittedFinancialTransaction() {

        Account sender =
                saveAccount(
                        new BigDecimal("10000.00")
                );

        Account receiver =
                saveAccount(
                        new BigDecimal("5000.00")
                );

        PaymentCreatedEvent payment =
                event(
                        sender.getOwnerId(),
                        receiver.getOwnerId(),
                        "2000.00"
                );

        testPaymentIds.add(
                payment.getPaymentId()
        );

        doThrow(
                new IllegalStateException(
                        "Kafka publication exhausted"
                )
        ).when(
                ledgerEventProducer
        ).sendLedgerCompleted(
                any(LedgerCompletedEvent.class)
        );

        assertThrows(
                IllegalStateException.class,
                () -> ledgerService.processTransfer(
                        payment
                )
        );

        Account senderAfter =
                accountRepository
                        .findById(
                                sender.getId()
                        )
                        .orElseThrow();

        Account receiverAfter =
                accountRepository
                        .findById(
                                receiver.getId()
                        )
                        .orElseThrow();

        assertEquals(
                0,
                new BigDecimal("8000.00")
                        .compareTo(
                                senderAfter.getBalance()
                        )
        );

        assertEquals(
                0,
                new BigDecimal("7000.00")
                        .compareTo(
                                receiverAfter.getBalance()
                        )
        );

        assertEquals(
                2,
                ledgerRepository
                        .findByPaymentId(
                                payment.getPaymentId()
                        )
                        .size()
        );
    }
}