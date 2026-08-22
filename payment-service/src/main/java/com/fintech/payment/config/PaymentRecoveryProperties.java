package com.fintech.payment.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(
        prefix = "payment.recovery"
)
@Validated
@Getter
@Setter
public class PaymentRecoveryProperties {

    private boolean enabled = true;

    @Min(1000)
    private long processingTimeoutMs =
            300_000;

    @Min(1000)
    private long retryIntervalMs =
            60_000;

    @Min(1000)
    private long pollIntervalMs =
            30_000;

    @Min(1)
    private int maxAttempts = 3;

    @Min(1)
    private int batchSize = 20;
}