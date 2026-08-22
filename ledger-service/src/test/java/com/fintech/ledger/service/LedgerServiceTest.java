package com.fintech.ledger.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.mockito.InjectMocks;
import com.fintech.ledger.entity.LedgerProcessingResultType;
import com.fintech.ledger.repository.LedgerProcessingResultRepository;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private LedgerRepository ledgerRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerEventProducer ledgerEventProducer;

    @Mock
    private LedgerProcessingResultRepository processingResultRepository;

    @Mock
    private LedgerCacheService ledgerCacheService;

    @Mock
    private LedgerCacheMetrics ledgerCacheMetrics;

    @InjectMocks
    private LedgerService ledgerService;
    @BeforeEach
    void setUp() {


    }

    @Test
    void transfersMoneyFromSenderToReceiver() {

        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        Account sender =
                account(
                        senderId,
                        new BigDecimal("10000.00"),
                        AccountStatus.ACTIVE
                );

        Account receiver =
                account(
                        receiverId,
                        new BigDecimal("5000.00"),
                        AccountStatus.ACTIVE
                );

        stubAccounts(sender, receiver);

        ledgerService.processTransfer(
                event(
                        senderId,
                        receiverId,
                        "2000.00"
                )
        );

        assertEquals(
                new BigDecimal("8000.00"),
                sender.getBalance()
        );

        assertEquals(
                new BigDecimal("7000.00"),
                receiver.getBalance()
        );

        verify(accountRepository)
                .save(sender);

        verify(accountRepository)
                .save(receiver);

        verify(ledgerEventProducer)
                .sendLedgerCompleted(
                        any(LedgerCompletedEvent.class)
                );

        verify(
                ledgerEventProducer,
                never()
        ).sendLedgerFailed(
                any(LedgerFailedEvent.class)
        );
        verify(processingResultRepository)
                .save(
                        argThat(
                                result ->
                                        result.getResultType()
                                                == LedgerProcessingResultType.PROCESSED
                        )
                );
    }

    @Test
    void allowsExactBalanceTransfer() {

        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        Account sender =
                account(
                        senderId,
                        new BigDecimal("2000.00"),
                        AccountStatus.ACTIVE
                );

        Account receiver =
                account(
                        receiverId,
                        new BigDecimal("5000.00"),
                        AccountStatus.ACTIVE
                );

        stubAccounts(sender, receiver);

        ledgerService.processTransfer(
                event(
                        senderId,
                        receiverId,
                        "2000.00"
                )
        );

        assertEquals(
                new BigDecimal("0.00"),
                sender.getBalance()
        );

        assertEquals(
                new BigDecimal("7000.00"),
                receiver.getBalance()
        );
    }

    @Test
    void rejectsInsufficientBalanceWithoutChangingAccounts() {

        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        Account sender =
                account(
                        senderId,
                        new BigDecimal("1999.00"),
                        AccountStatus.ACTIVE
                );

        Account receiver =
                account(
                        receiverId,
                        new BigDecimal("5000.00"),
                        AccountStatus.ACTIVE
                );

        stubAccounts(sender, receiver);

        ledgerService.processTransfer(
                event(
                        senderId,
                        receiverId,
                        "2000.00"
                )
        );

        assertEquals(
                new BigDecimal("1999.00"),
                sender.getBalance()
        );

        assertEquals(
                new BigDecimal("5000.00"),
                receiver.getBalance()
        );

        verify(
                accountRepository,
                never()
        ).save(any(Account.class));

        verifyFailedReason(
                "INSUFFICIENT_BALANCE"
        );

        verify(processingResultRepository)
                .save(
                        argThat(
                                result ->
                                        result.getResultType()
                                                == LedgerProcessingResultType.FAILED
                                                &&
                                                "INSUFFICIENT_BALANCE"
                                                        .equals(
                                                                result.getFailureReason()
                                                        )
                        )
                );
    }

    @Test
    void rejectsMissingSender() {

        // Ensure senderId < receiverId so sender is locked first
        UUID senderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID receiverId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        when(
                accountRepository.findByOwnerIdForUpdate(senderId)
        ).thenReturn(Optional.empty());

        ledgerService.processTransfer(
                event(
                        senderId,
                        receiverId,
                        "2000.00"
                )
        );

        verify(
                accountRepository
        ).findByOwnerIdForUpdate(senderId);

        verify(
                accountRepository,
                never()
        ).findByOwnerIdForUpdate(receiverId);

        verify(
                accountRepository,
                never()
        ).save(any(Account.class));

        verifyFailedReason(
                "ACCOUNT_NOT_FOUND"
        );
    }

    @Test
    void rejectsMissingReceiver() {

        // Ensure senderId < receiverId so sender is locked first
        UUID senderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID receiverId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        Account sender =
                account(
                        senderId,
                        new BigDecimal("10000.00"),
                        AccountStatus.ACTIVE
                );

        when(
                accountRepository.findByOwnerIdForUpdate(senderId)
        ).thenReturn(Optional.of(sender));

        when(
                accountRepository.findByOwnerIdForUpdate(receiverId)
        ).thenReturn(Optional.empty());

        ledgerService.processTransfer(
                event(
                        senderId,
                        receiverId,
                        "2000.00"
                )
        );

        verify(
                accountRepository,
                never()
        ).save(any(Account.class));

        verifyFailedReason(
                "ACCOUNT_NOT_FOUND"
        );
    }

    @Test
    void rejectsInactiveSender() {

        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        Account sender =
                account(
                        senderId,
                        new BigDecimal("10000.00"),
                        AccountStatus.INACTIVE
                );

        Account receiver =
                account(
                        receiverId,
                        new BigDecimal("5000.00"),
                        AccountStatus.ACTIVE
                );

        stubAccounts(sender, receiver);

        ledgerService.processTransfer(
                event(
                        senderId,
                        receiverId,
                        "2000.00"
                )
        );

        assertEquals(
                new BigDecimal("10000.00"),
                sender.getBalance()
        );

        assertEquals(
                new BigDecimal("5000.00"),
                receiver.getBalance()
        );

        verify(
                accountRepository,
                never()
        ).save(any(Account.class));

        verifyFailedReason(
                "ACCOUNT_INACTIVE"
        );
    }

    @Test
    void rejectsInactiveReceiver() {

        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        Account sender =
                account(
                        senderId,
                        new BigDecimal("10000.00"),
                        AccountStatus.ACTIVE
                );

        Account receiver =
                account(
                        receiverId,
                        new BigDecimal("5000.00"),
                        AccountStatus.INACTIVE
                );

        stubAccounts(sender, receiver);

        ledgerService.processTransfer(
                event(
                        senderId,
                        receiverId,
                        "2000.00"
                )
        );

        assertEquals(
                new BigDecimal("10000.00"),
                sender.getBalance()
        );

        assertEquals(
                new BigDecimal("5000.00"),
                receiver.getBalance()
        );

        verify(
                accountRepository,
                never()
        ).save(any(Account.class));

        verifyFailedReason(
                "ACCOUNT_INACTIVE"
        );
    }

    @Test
    void rejectsCurrencyMismatch() {

        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        Account sender =
                account(
                        senderId,
                        new BigDecimal("10000.00"),
                        AccountStatus.ACTIVE
                );

        Account receiver =
                account(
                        receiverId,
                        new BigDecimal("5000.00"),
                        AccountStatus.ACTIVE
                );

        receiver.setCurrency("USD");

        stubAccounts(sender, receiver);

        ledgerService.processTransfer(
                event(
                        senderId,
                        receiverId,
                        "2000.00"
                )
        );

        assertEquals(
                new BigDecimal("10000.00"),
                sender.getBalance()
        );

        assertEquals(
                new BigDecimal("5000.00"),
                receiver.getBalance()
        );

        verify(
                accountRepository,
                never()
        ).save(any(Account.class));

        verifyFailedReason(
                "CURRENCY_MISMATCH"
        );
    }

    @Test
    void rejectsInvalidAmount() {

        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        ledgerService.processTransfer(
                event(
                        senderId,
                        receiverId,
                        "0.00"
                )
        );

        verifyNoInteractions(
                accountRepository
        );

        verifyFailedReason(
                "INVALID_TRANSFER"
        );
    }

    @Test
    void rejectsNullPaymentEvent() {

        ledgerService.processTransfer(null);

        verifyNoInteractions(
                accountRepository
        );

        verifyFailedReason(
                "INVALID_TRANSFER"
        );
    }

    @Test
    void createsDebitAndCreditForSamePayment() {

        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        Account sender =
                account(
                        senderId,
                        new BigDecimal("10000.00"),
                        AccountStatus.ACTIVE
                );

        Account receiver =
                account(
                        receiverId,
                        new BigDecimal("5000.00"),
                        AccountStatus.ACTIVE
                );

        PaymentCreatedEvent payment =
                event(
                        senderId,
                        receiverId,
                        "2000.00"
                );

        stubAccounts(sender, receiver);

        when(
                ledgerRepository.existsByPaymentId(
                        payment.getPaymentId()
                )
        ).thenReturn(false);

        ledgerService.processTransfer(payment);

        ArgumentCaptor<List<LedgerEntry>> captor =
                ArgumentCaptor.forClass(List.class);

        verify(ledgerRepository)
                .saveAll(captor.capture());

        List<LedgerEntry> entries =
                captor.getValue();

        assertEquals(
                2,
                entries.size()
        );

        LedgerEntry debit =
                entries.stream()
                        .filter(
                                entry ->
                                        entry.getEntryType()
                                                == LedgerEntryType.DEBIT
                        )
                        .findFirst()
                        .orElseThrow();

        LedgerEntry credit =
                entries.stream()
                        .filter(
                                entry ->
                                        entry.getEntryType()
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

        assertEquals(
                debit.getAmount(),
                credit.getAmount()
        );

        verify(processingResultRepository)
                .save(
                        argThat(
                                result ->
                                        result.getResultType()
                                                == LedgerProcessingResultType.PROCESSED
                        )
                );
    }

    @Test
    void duplicatePaymentIsIgnoredAfterItWasPosted() {

        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();

        Account sender =
                account(
                        senderId,
                        new BigDecimal("10000.00"),
                        AccountStatus.ACTIVE
                );

        Account receiver =
                account(
                        receiverId,
                        new BigDecimal("5000.00"),
                        AccountStatus.ACTIVE
                );

        PaymentCreatedEvent payment =
                event(
                        senderId,
                        receiverId,
                        "2000.00"
                );

        stubAccounts(sender, receiver);

        UUID existingLedgerEntryId = UUID.randomUUID();
        LedgerEntry existingDebit = LedgerEntry.builder()
                .id(existingLedgerEntryId)
                .paymentId(payment.getPaymentId())
                .entryType(LedgerEntryType.DEBIT)
                .amount(new BigDecimal("2000.00"))
                .accountId(sender.getId())
                .build();

        when(
                ledgerRepository.existsByPaymentId(
                        payment.getPaymentId()
                )
        ).thenReturn(true);

        when(
                ledgerRepository.findByPaymentId(
                        payment.getPaymentId()
                )
        ).thenReturn(List.of(existingDebit));

        ledgerService.processTransfer(payment);

        assertEquals(
                new BigDecimal("10000.00"),
                sender.getBalance()
        );

        assertEquals(
                new BigDecimal("5000.00"),
                receiver.getBalance()
        );

        verify(
                accountRepository,
                never()
        ).save(any(Account.class));

        verify(
                ledgerRepository,
                never()
        ).saveAll(any());

        verify(ledgerEventProducer)
                .sendLedgerCompleted(
                        any(LedgerCompletedEvent.class)
                );
    }

    private void stubAccounts(
            Account sender,
            Account receiver
    ) {

        when(
                accountRepository.findByOwnerIdForUpdate(
                        sender.getOwnerId()
                )
        ).thenReturn(
                Optional.of(sender)
        );

        when(
                accountRepository.findByOwnerIdForUpdate(
                        receiver.getOwnerId()
                )
        ).thenReturn(
                Optional.of(receiver)
        );
    }

    private void verifyFailedReason(
            String expectedReason
    ) {

        ArgumentCaptor<LedgerFailedEvent> captor =
                ArgumentCaptor.forClass(
                        LedgerFailedEvent.class
                );

        verify(ledgerEventProducer)
                .sendLedgerFailed(
                        captor.capture()
                );

        assertEquals(
                expectedReason,
                captor.getValue().getReason()
        );

        verify(
                ledgerEventProducer,
                never()
        ).sendLedgerCompleted(
                any(LedgerCompletedEvent.class)
        );
    }

    private static Account account(
            UUID ownerId,
            BigDecimal balance,
            AccountStatus status
    ) {

        return Account.builder()
                .id(UUID.randomUUID())
                .ownerId(ownerId)
                .accountType("WALLET")
                .balance(balance)
                .currency("INR")
                .status(status)
                .build();
    }

    private static PaymentCreatedEvent event(
            UUID senderId,
            UUID receiverId,
            String amount
    ) {

        return new PaymentCreatedEvent(
                UUID.randomUUID(),
                senderId,
                receiverId,
                new BigDecimal(amount),
                "INR",
                "story2-test-" + UUID.randomUUID(),
                "PROCESSING",
                null
        );
    }

}