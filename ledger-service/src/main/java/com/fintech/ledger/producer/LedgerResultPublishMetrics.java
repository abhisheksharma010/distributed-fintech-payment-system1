package com.fintech.ledger.producer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class LedgerResultPublishMetrics {

    private final Counter success;
    private final Counter retry;
    private final Counter failure;

    public LedgerResultPublishMetrics(
            MeterRegistry registry
    ) {

        this.success =
                Counter.builder(
                                "ledger.result.publish.success"
                        )
                        .description(
                                "Successful Ledger result publications"
                        )
                        .register(registry);

        this.retry =
                Counter.builder(
                                "ledger.result.publish.retry"
                        )
                        .description(
                                "Ledger result publication retry attempts"
                        )
                        .register(registry);

        this.failure =
                Counter.builder(
                                "ledger.result.publish.failure"
                        )
                        .description(
                                "Exhausted Ledger result publications"
                        )
                        .register(registry);
    }

    public void recordSuccess() {
        success.increment();
    }

    public void recordRetry() {
        retry.increment();
    }

    public void recordFailure() {
        failure.increment();
    }
}