package com.fintech.ledger.service;

import com.fintech.ledger.entity.Account;
import com.fintech.ledger.entity.LedgerEntryType;
import com.fintech.ledger.repository.AccountRepository;
import com.fintech.ledger.repository.LedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerReconciliationServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerRepository ledgerRepository;

    @Mock
    private LedgerReconciliationMetrics metrics;

    private LedgerReconciliationService
            reconciliationService;

    @BeforeEach
    void setUp() {

        reconciliationService =
                new LedgerReconciliationService(
                        accountRepository,
                        ledgerRepository,
                        metrics
                );
    }

    @Test
    void healthyAccountMatchesLedgerHistory() {

        UUID accountId =
                UUID.randomUUID();

        Account account =
                account(
                        accountId,
                        "10000.00",
                        "8000.00"
                );

        when(
                accountRepository
                        .findById(accountId)
        ).thenReturn(
                Optional.of(account)
        );

        when(
                ledgerRepository
                        .sumAmountByAccountIdAndEntryType(
                                accountId,
                                LedgerEntryType.CREDIT
                        )
        ).thenReturn(
                new BigDecimal("1000.00")
        );

        when(
                ledgerRepository
                        .sumAmountByAccountIdAndEntryType(
                                accountId,
                                LedgerEntryType.DEBIT
                        )
        ).thenReturn(
                new BigDecimal("3000.00")
        );

        var result =
                reconciliationService
                        .reconcileAccount(
                                accountId
                        );

        assertEquals(
                0,
                new BigDecimal("8000.00")
                        .compareTo(
                                result.expectedBalance()
                        )
        );

        assertEquals(
                0,
                new BigDecimal("8000.00")
                        .compareTo(
                                result.actualBalance()
                        )
        );

        assertEquals(
                0,
                BigDecimal.ZERO
                        .compareTo(
                                result.difference()
                        )
        );

        assertEquals(
                ReconciliationStatus.HEALTHY,
                result.status()
        );

        verify(metrics)
                .recordChecked();

        verify(
                metrics,
                never()
        ).recordMismatch();
    }

    @Test
    void mismatchIsDetected() {

        UUID accountId =
                UUID.randomUUID();

        Account account =
                account(
                        accountId,
                        "10000.00",
                        "9000.00"
                );

        when(
                accountRepository
                        .findById(accountId)
        ).thenReturn(
                Optional.of(account)
        );

        when(
                ledgerRepository
                        .sumAmountByAccountIdAndEntryType(
                                accountId,
                                LedgerEntryType.CREDIT
                        )
        ).thenReturn(
                BigDecimal.ZERO
        );

        when(
                ledgerRepository
                        .sumAmountByAccountIdAndEntryType(
                                accountId,
                                LedgerEntryType.DEBIT
                        )
        ).thenReturn(
                new BigDecimal("2000.00")
        );

        var result =
                reconciliationService
                        .reconcileAccount(
                                accountId
                        );

        assertEquals(
                ReconciliationStatus
                        .RECONCILIATION_MISMATCH,
                result.status()
        );

        assertEquals(
                0,
                new BigDecimal("8000.00")
                        .compareTo(
                                result.expectedBalance()
                        )
        );

        assertEquals(
                0,
                new BigDecimal("9000.00")
                        .compareTo(
                                result.actualBalance()
                        )
        );

        assertEquals(
                0,
                new BigDecimal("1000.00")
                        .compareTo(
                                result.difference()
                        )
        );

        verify(metrics)
                .recordChecked();

        verify(metrics)
                .recordMismatch();
    }

    @Test
    void reconciliationNeverRepairsBalance() {

        UUID accountId =
                UUID.randomUUID();

        Account account =
                account(
                        accountId,
                        "10000.00",
                        "9000.00"
                );

        when(
                accountRepository
                        .findById(accountId)
        ).thenReturn(
                Optional.of(account)
        );

        when(
                ledgerRepository
                        .sumAmountByAccountIdAndEntryType(
                                accountId,
                                LedgerEntryType.CREDIT
                        )
        ).thenReturn(
                BigDecimal.ZERO
        );

        when(
                ledgerRepository
                        .sumAmountByAccountIdAndEntryType(
                                accountId,
                                LedgerEntryType.DEBIT
                        )
        ).thenReturn(
                new BigDecimal("2000.00")
        );

        reconciliationService
                .reconcileAccount(
                        accountId
                );

        assertEquals(
                0,
                new BigDecimal("9000.00")
                        .compareTo(
                                account.getBalance()
                        )
        );

        verify(
                accountRepository,
                never()
        ).save(any());

        verify(
                accountRepository,
                never()
        ).saveAndFlush(any());
    }

    @Test
    void zeroBalanceAccountWithNoHistoryIsHealthy() {

        UUID accountId =
                UUID.randomUUID();

        Account account =
                account(
                        accountId,
                        "0.00",
                        "0.00"
                );

        when(
                accountRepository
                        .findById(accountId)
        ).thenReturn(
                Optional.of(account)
        );

        when(
                ledgerRepository
                        .sumAmountByAccountIdAndEntryType(
                                accountId,
                                LedgerEntryType.CREDIT
                        )
        ).thenReturn(
                BigDecimal.ZERO
        );

        when(
                ledgerRepository
                        .sumAmountByAccountIdAndEntryType(
                                accountId,
                                LedgerEntryType.DEBIT
                        )
        ).thenReturn(
                BigDecimal.ZERO
        );

        var result =
                reconciliationService
                        .reconcileAccount(
                                accountId
                        );

        assertEquals(
                ReconciliationStatus.HEALTHY,
                result.status()
        );
    }

    private static Account account(
            UUID accountId,
            String openingBalance,
            String actualBalance
    ) {

        return Account.builder()
                .id(accountId)
                .ownerId(
                        UUID.randomUUID()
                )
                .balance(
                        new BigDecimal(
                                actualBalance
                        )
                )
                .openingBalance(
                        new BigDecimal(
                                openingBalance
                        )
                )
                .currency("INR")
                .build();
    }
}