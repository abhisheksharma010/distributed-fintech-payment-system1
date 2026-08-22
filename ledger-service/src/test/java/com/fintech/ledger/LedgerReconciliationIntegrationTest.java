package com.fintech.ledger;

import com.fintech.ledger.entity.Account;
import com.fintech.ledger.entity.AccountStatus;
import com.fintech.ledger.entity.LedgerEntry;
import com.fintech.ledger.entity.LedgerEntryType;
import com.fintech.ledger.repository.AccountRepository;
import com.fintech.ledger.repository.LedgerRepository;
import com.fintech.ledger.service.LedgerReconciliationService;
import com.fintech.ledger.service.ReconciliationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        properties = {
                "spring.kafka.listener.auto-startup=false",
                "ledger.reconciliation.enabled=false"
        }
)
class LedgerReconciliationIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private LedgerReconciliationService reconciliationService;

    private UUID accountId;

    @AfterEach
    void cleanup() {

        if (accountId == null) {
            return;
        }

        ledgerRepository.deleteByAccountIdIn(
                List.of(accountId)
        );

        accountRepository
                .findById(accountId)
                .ifPresent(
                        accountRepository::delete
                );
    }

    @Test
    void healthyAccountReconcilesFromOpeningBalanceAndLedgerHistory() {

        Account account =
                persistAccount(
                        "10000.00",
                        "8000.00"
                );

        persistEntry(
                account.getId(),
                LedgerEntryType.DEBIT,
                "2000.00"
        );

        var result =
                reconciliationService
                        .reconcileAccount(
                                account.getId()
                        );

        assertEquals(
                ReconciliationStatus.HEALTHY,
                result.status()
        );

        assertMoneyEquals(
                "10000.00",
                result.openingBalance()
        );

        assertMoneyEquals(
                "0.00",
                result.totalCredits()
        );

        assertMoneyEquals(
                "2000.00",
                result.totalDebits()
        );

        assertMoneyEquals(
                "8000.00",
                result.expectedBalance()
        );

        assertMoneyEquals(
                "8000.00",
                result.actualBalance()
        );

        assertMoneyEquals(
                "0.00",
                result.difference()
        );
    }

    @Test
    void corruptedStoredBalanceProducesReconciliationMismatch() {

        Account account =
                persistAccount(
                        "10000.00",
                        "8000.00"
                );

        persistEntry(
                account.getId(),
                LedgerEntryType.DEBIT,
                "2000.00"
        );

        /*
         * Ledger says:
         *
         * opening 10000
         * - debit 2000
         * = expected 8000
         *
         * Deliberately corrupt materialized account
         * balance to 9000.
         */
        account.setBalance(
                new BigDecimal(
                        "9000.00"
                )
        );

        accountRepository
                .saveAndFlush(
                        account
                );

        var result =
                reconciliationService
                        .reconcileAccount(
                                account.getId()
                        );

        assertEquals(
                ReconciliationStatus
                        .RECONCILIATION_MISMATCH,
                result.status()
        );

        assertMoneyEquals(
                "10000.00",
                result.openingBalance()
        );

        assertMoneyEquals(
                "0.00",
                result.totalCredits()
        );

        assertMoneyEquals(
                "2000.00",
                result.totalDebits()
        );

        assertMoneyEquals(
                "8000.00",
                result.expectedBalance()
        );

        assertMoneyEquals(
                "9000.00",
                result.actualBalance()
        );

        assertMoneyEquals(
                "1000.00",
                result.difference()
        );

        /*
         * Reconciliation is detection-only.
         * It must NEVER silently repair financial data.
         */
        Account reloaded =
                accountRepository
                        .findById(
                                account.getId()
                        )
                        .orElseThrow();

        assertMoneyEquals(
                "9000.00",
                reloaded.getBalance()
        );
    }

    @Test
    void reconciliationSupportsBothDebitAndCreditHistory() {

        Account account =
                persistAccount(
                        "5000.00",
                        "6500.00"
                );

        persistEntry(
                account.getId(),
                LedgerEntryType.CREDIT,
                "2000.00"
        );

        persistEntry(
                account.getId(),
                LedgerEntryType.DEBIT,
                "500.00"
        );

        /*
         * 5000 + 2000 - 500 = 6500
         */
        var result =
                reconciliationService
                        .reconcileAccount(
                                account.getId()
                        );

        assertEquals(
                ReconciliationStatus.HEALTHY,
                result.status()
        );

        assertMoneyEquals(
                "2000.00",
                result.totalCredits()
        );

        assertMoneyEquals(
                "500.00",
                result.totalDebits()
        );

        assertMoneyEquals(
                "6500.00",
                result.expectedBalance()
        );

        assertMoneyEquals(
                "6500.00",
                result.actualBalance()
        );
    }

    @Test
    void zeroBalanceAccountWithNoLedgerHistoryIsHealthy() {

        Account account =
                persistAccount(
                        "0.00",
                        "0.00"
                );

        var result =
                reconciliationService
                        .reconcileAccount(
                                account.getId()
                        );

        assertEquals(
                ReconciliationStatus.HEALTHY,
                result.status()
        );

        assertMoneyEquals(
                "0.00",
                result.expectedBalance()
        );

        assertMoneyEquals(
                "0.00",
                result.actualBalance()
        );

        assertMoneyEquals(
                "0.00",
                result.difference()
        );
    }

    private Account persistAccount(
            String openingBalance,
            String currentBalance
    ) {

        accountId =
                UUID.randomUUID();

        Account account =
                Account.builder()
                        .id(accountId)
                        .ownerId(
                                UUID.randomUUID()
                        )
                        .accountType(
                                "WALLET"
                        )
                        .balance(
                                new BigDecimal(
                                        currentBalance
                                )
                        )
                        .openingBalance(
                                new BigDecimal(
                                        openingBalance
                                )
                        )
                        .currency("INR")
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

    private void persistEntry(
            UUID accountId,
            LedgerEntryType type,
            String amount
    ) {

        LedgerEntry entry =
                LedgerEntry.builder()
                        .accountId(accountId)
                        .paymentId(
                                UUID.randomUUID()
                        )
                        .entryType(type)
                        .amount(
                                new BigDecimal(
                                        amount
                                )
                        )
                        .createdAt(
                                LocalDateTime.now()
                        )
                        .build();

        ledgerRepository
                .saveAndFlush(
                        entry
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