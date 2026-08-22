package com.fintech.ledger.service;

import com.fintech.ledger.entity.Account;
import com.fintech.ledger.entity.LedgerEntryType;
import com.fintech.ledger.repository.AccountRepository;
import com.fintech.ledger.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerReconciliationService {

    private final AccountRepository
            accountRepository;

    private final LedgerRepository
            ledgerRepository;

    private final LedgerReconciliationMetrics
            metrics;

    @Transactional(readOnly = true)
    public AccountReconciliationResult
    reconcileAccount(
            UUID accountId
    ) {

        Account account =
                accountRepository
                        .findById(accountId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Account not found: "
                                                        + accountId
                                        )
                        );

        BigDecimal credits =
                ledgerRepository
                        .sumAmountByAccountIdAndEntryType(
                                accountId,
                                LedgerEntryType.CREDIT
                        );

        BigDecimal debits =
                ledgerRepository
                        .sumAmountByAccountIdAndEntryType(
                                accountId,
                                LedgerEntryType.DEBIT
                        );

        BigDecimal openingBalance =
                account.getOpeningBalance();

        BigDecimal expectedBalance =
                openingBalance
                        .add(credits)
                        .subtract(debits);

        BigDecimal actualBalance =
                account.getBalance();

        BigDecimal difference =
                actualBalance
                        .subtract(
                                expectedBalance
                        );

        ReconciliationStatus status =
                difference
                        .compareTo(
                                BigDecimal.ZERO
                        )
                        == 0
                        ?
                        ReconciliationStatus.HEALTHY
                        :
                        ReconciliationStatus
                        .RECONCILIATION_MISMATCH;

        metrics.recordChecked();

        if (
                status
                        ==
                        ReconciliationStatus
                                .RECONCILIATION_MISMATCH
        ) {

            metrics.recordMismatch();

            log.error(
                    "Ledger reconciliation mismatch accountId={} openingBalance={} credits={} debits={} expectedBalance={} actualBalance={} difference={}",
                    accountId,
                    openingBalance,
                    credits,
                    debits,
                    expectedBalance,
                    actualBalance,
                    difference
            );

        } else {

            log.info(
                    "Ledger reconciliation healthy accountId={} expectedBalance={} actualBalance={}",
                    accountId,
                    expectedBalance,
                    actualBalance
            );
        }

        return new AccountReconciliationResult(
                accountId,
                openingBalance,
                credits,
                debits,
                expectedBalance,
                actualBalance,
                difference,
                status
        );
    }

    @Transactional(readOnly = true)
    public List<AccountReconciliationResult>
    reconcileAllAccounts() {

        return accountRepository
                .findAll()
                .stream()
                .map(
                        account ->
                                reconcileAccount(
                                        account.getId()
                                )
                )
                .toList();
    }
}