package com.fintech.ledger.controller;

import com.fintech.ledger.dto.LedgerRecoveryResponse;
import com.fintech.ledger.service.LedgerRecoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping(
        "/internal/ledger/payments"
)
@RequiredArgsConstructor
public class LedgerRecoveryController {

    private static final String
            INTERNAL_RECOVERY_HEADER =
            "X-INTERNAL-RECOVERY-KEY";

    private final LedgerRecoveryService
            recoveryService;

    @Value(
            "${ledger.recovery.internal-key:local-ledger-recovery-key}"
    )
    private String configuredInternalKey;

    @GetMapping("/{paymentId}")
    public LedgerRecoveryResponse lookup(
            @PathVariable
            UUID paymentId,

            @RequestHeader(
                    value = INTERNAL_RECOVERY_HEADER,
                    required = false
            )
            String internalKey
    ) {

        if (
                internalKey == null
                        ||
                        !configuredInternalKey
                                .equals(
                                        internalKey
                                )
        ) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid internal recovery context"
            );
        }

        return recoveryService.lookup(
                paymentId
        );
    }
}