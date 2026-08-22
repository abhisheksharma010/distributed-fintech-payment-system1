package com.fintech.payment.service;

import com.fintech.payment.config.LedgerRecoveryProperties;
import com.fintech.payment.dto.LedgerRecoveryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class LedgerRecoveryClient {

    private static final String
            INTERNAL_RECOVERY_HEADER =
            "X-INTERNAL-RECOVERY-KEY";

    private final RestClient
            ledgerRecoveryRestClient;

    private final LedgerRecoveryProperties
            properties;

    public LedgerRecoveryResponse lookup(
            UUID paymentId
    ) {

        try {

            LedgerRecoveryResponse response =
                    ledgerRecoveryRestClient
                            .get()
                            .uri(
                                    "/internal/ledger/payments/{paymentId}",
                                    paymentId
                            )
                            .header(
                                    INTERNAL_RECOVERY_HEADER,
                                    properties
                                            .getInternalKey()
                            )
                            .retrieve()
                            .body(
                                    LedgerRecoveryResponse.class
                            );

            if (response == null) {

                throw new IllegalStateException(
                        "Ledger recovery response was empty"
                );
            }

            return response;

        } catch (
                RestClientException ex
        ) {

            throw new LedgerRecoveryUnavailableException(
                    paymentId,
                    ex
            );
        }
    }

    public static class
    LedgerRecoveryUnavailableException
            extends RuntimeException {

        public LedgerRecoveryUnavailableException(
                UUID paymentId,
                Throwable cause
        ) {

            super(
                    "Ledger reconciliation unavailable for paymentId="
                            + paymentId,
                    cause
            );
        }
    }
}