package com.fintech.ledger.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class LedgerReconciliationMetrics {

    private final Counter checked;
    private final Counter mismatch;

    public LedgerReconciliationMetrics(
            MeterRegistry registry
    ) {

        this.checked =
                Counter.builder(
                                "ledger.reconciliation.checked"
                        )
                        .description(
                                "Accounts checked by Ledger reconciliation"
                        )
                        .register(registry);

        this.mismatch =
                Counter.builder(
                                "ledger.reconciliation.mismatch"
                        )
                        .description(
                                "Ledger balance reconciliation mismatches"
                        )
                        .register(registry);
    }

    public void recordChecked() {
        checked.increment();
    }

    public void recordMismatch() {
        mismatch.increment();
    }
}