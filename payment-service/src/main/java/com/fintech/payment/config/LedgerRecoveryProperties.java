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
        prefix = "ledger.recovery"
)
@Validated
@Getter
@Setter
public class LedgerRecoveryProperties {

    @NotBlank
    private String baseUrl =
            "http://localhost:8084";

    @NotBlank
    private String internalKey =
            "local-ledger-recovery-key";

    @Min(100)
    private int connectTimeoutMs =
            2000;

    @Min(100)
    private int readTimeoutMs =
            3000;
}