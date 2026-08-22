package com.fintech.ledger.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        name = "ledger.reconciliation.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class LedgerReconciliationJob {

    private final LedgerReconciliationService
            reconciliationService;

    @Scheduled(
            fixedDelayString =
                    "${ledger.reconciliation.interval-ms:300000}"
    )
    public void reconcile() {

        try {

            var results =
                    reconciliationService
                            .reconcileAllAccounts();

            long mismatches =
                    results.stream()
                            .filter(
                                    result ->
                                            result.status()
                                                    ==
                                                    ReconciliationStatus
                                                            .RECONCILIATION_MISMATCH
                            )
                            .count();

            log.info(
                    "Ledger reconciliation scan completed checked={} mismatches={}",
                    results.size(),
                    mismatches
            );

        } catch (Exception ex) {

            log.error(
                    "Ledger reconciliation scan failed",
                    ex
            );
        }
    }
}