package com.fintech.payment.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(
        prefix = "outbox.publisher"
)
@Validated
@Getter
@Setter
public class OutboxPublisherProperties {

    private boolean enabled = true;

    @Min(1)
    private long pollIntervalMs = 2000;

    @Min(1)
    private int batchSize = 50;

    @Min(1)
    private long sendTimeoutMs = 10000;

    @NotBlank
    private String topic =
            "payments.created";
}