package com.fintech.payment.service;

import com.fintech.payment.repository.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics {

    private final Counter publishSuccess;
    private final Counter publishFailure;

    public OutboxMetrics(
            MeterRegistry meterRegistry,
            OutboxRepository outboxRepository
    ) {

        this.publishSuccess =
                Counter.builder(
                                "outbox.publish.success"
                        )
                        .description(
                                "Successfully acknowledged outbox publishes"
                        )
                        .register(meterRegistry);

        this.publishFailure =
                Counter.builder(
                                "outbox.publish.failure"
                        )
                        .description(
                                "Failed outbox publish attempts"
                        )
                        .register(meterRegistry);

        Gauge.builder(
                        "outbox.pending",
                        outboxRepository,
                        repository ->
                                repository
                                        .countByPublishedFalse()
                )
                .description(
                        "Pending unpublished outbox events"
                )
                .register(meterRegistry);
    }

    public void recordSuccess() {
        publishSuccess.increment();
    }

    public void recordFailure() {
        publishFailure.increment();
    }
}