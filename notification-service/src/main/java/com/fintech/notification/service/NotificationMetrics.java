package com.fintech.notification.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class NotificationMetrics {

    private final Counter created;
    private final Counter sent;
    private final Counter failed;
    private final Counter duplicate;

    public NotificationMetrics(
            MeterRegistry meterRegistry
    ) {

        this.created =
                meterRegistry.counter(
                        "notifications.created"
                );

        this.sent =
                meterRegistry.counter(
                        "notifications.sent"
                );

        this.failed =
                meterRegistry.counter(
                        "notifications.failed"
                );

        this.duplicate =
                meterRegistry.counter(
                        "notifications.duplicate"
                );
    }

    public void recordCreated() {
        created.increment();
    }

    public void recordSent() {
        sent.increment();
    }

    public void recordFailed() {
        failed.increment();
    }

    public void recordDuplicate() {
        duplicate.increment();
    }
}