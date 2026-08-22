package com.fintech.payment.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PaymentSagaMetrics {

    private final Counter completed;
    private final Counter failed;
    private final Counter invalidTransition;
    private final Counter unknownPayment;

    @Autowired
    public PaymentSagaMetrics(MeterRegistry registry) {
        this(
                Counter.builder("payments.saga.completed")
                        .description(
                                "Payments completed by the Payment-Ledger Saga"
                        )
                        .register(registry),

                Counter.builder("payments.saga.failed")
                        .description(
                                "Payments failed by the Payment-Ledger Saga"
                        )
                        .register(registry),

                Counter.builder(
                                "payments.saga.invalid_transition"
                        )
                        .description(
                                "Ignored invalid or conflicting Saga result transitions"
                        )
                        .register(registry),

                Counter.builder(
                                "payments.saga.unknown_payment"
                        )
                        .description(
                                "Ledger result events for unknown payments"
                        )
                        .register(registry)
        );
    }

    private PaymentSagaMetrics(
            Counter completed,
            Counter failed,
            Counter invalidTransition,
            Counter unknownPayment
    ) {
        this.completed = completed;
        this.failed = failed;
        this.invalidTransition =
                invalidTransition;
        this.unknownPayment =
                unknownPayment;
    }

    static PaymentSagaMetrics noop() {
        return new PaymentSagaMetrics(
                null,
                null,
                null,
                null
        );
    }

    public void recordCompleted() {
        if (completed != null) {
            completed.increment();
        }
    }

    public void recordFailed() {
        if (failed != null) {
            failed.increment();
        }
    }

    public void recordInvalidTransition() {
        if (invalidTransition != null) {
            invalidTransition.increment();
        }
    }

    public void recordUnknownPayment() {
        if (unknownPayment != null) {
            unknownPayment.increment();
        }
    }
}