package com.fintech.ledger.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(
        prefix = "ledger.reconciliation"
)
@Validated
@Getter
@Setter
public class LedgerReconciliationProperties {

    private boolean enabled = true;

    @Min(1000)
    private long intervalMs = 300_000;
}