package com.fintech.payment.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class StuckPaymentRecoveryMetrics {

    private final Counter detected;
    private final Counter recovered;
    private final Counter failed;
    private final Counter retried;

    public StuckPaymentRecoveryMetrics(
            MeterRegistry registry
    ) {

        this.detected =
                Counter.builder(
                                "payments.stuck.detected"
                        )
                        .description(
                                "Stuck PROCESSING payments detected"
                        )
                        .register(registry);

        this.recovered =
                Counter.builder(
                                "payments.stuck.recovered"
                        )
                        .description(
                                "Stuck payments reconciled from Ledger state"
                        )
                        .register(registry);

        this.failed =
                Counter.builder(
                                "payments.stuck.failed"
                        )
                        .description(
                                "Stuck payments failed after recovery exhaustion"
                        )
                        .register(registry);

        this.retried =
                Counter.builder(
                                "payments.stuck.retry"
                        )
                        .description(
                                "Stuck payment workflow retries requested"
                        )
                        .register(registry);
    }

    public void recordDetected() {
        detected.increment();
    }

    public void recordRecovered() {
        recovered.increment();
    }

    public void recordFailed() {
        failed.increment();
    }

    public void recordRetry() {
        retried.increment();
    }
}