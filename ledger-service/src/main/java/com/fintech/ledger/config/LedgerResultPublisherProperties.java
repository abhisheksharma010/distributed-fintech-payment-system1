package com.fintech.ledger.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "ledger-result")
@Validated
@Getter
@Setter
public class LedgerResultPublisherProperties {

    @NotBlank
    private String completedTopic = "ledger.completed";

    @NotBlank
    private String failedTopic = "ledger.failed";

    @Min(1)
    private int maxAttempts = 3;

    @Min(0)
    private long backoffMs = 500;

    @Min(100)
    private long sendTimeoutMs = 10_000;
}