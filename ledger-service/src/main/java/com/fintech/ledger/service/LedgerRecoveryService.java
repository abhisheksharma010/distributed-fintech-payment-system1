package com.fintech.ledger.service;

import com.fintech.ledger.dto.LedgerRecoveryResponse;
import com.fintech.ledger.dto.LedgerRecoveryState;
import com.fintech.ledger.entity.LedgerEntry;
import com.fintech.ledger.entity.LedgerEntryType;
import com.fintech.ledger.entity.LedgerProcessingResult;
import com.fintech.ledger.entity.LedgerProcessingResultType;
import com.fintech.ledger.repository.LedgerProcessingResultRepository;
import com.fintech.ledger.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LedgerRecoveryService {

    private final LedgerProcessingResultRepository
            processingResultRepository;

    private final LedgerRepository
            ledgerRepository;

    @Transactional(readOnly = true)
    public LedgerRecoveryResponse lookup(
            UUID paymentId
    ) {

        return processingResultRepository
                .findById(paymentId)
                .map(
                        result ->
                                toResponse(
                                        result
                                )
                )
                .orElseGet(
                        () ->
                                lookupLegacyLedgerEntries(
                                        paymentId
                                )
                );
    }

    private LedgerRecoveryResponse toResponse(
            LedgerProcessingResult result
    ) {

        if (
                result.getResultType()
                        ==
                        LedgerProcessingResultType.PROCESSED
        ) {

            return new LedgerRecoveryResponse(
                    result.getPaymentId(),
                    LedgerRecoveryState.PROCESSED,
                    result.getLedgerTransactionId(),
                    null,
                    result.getResolvedAt()
            );
        }

        return new LedgerRecoveryResponse(
                result.getPaymentId(),
                LedgerRecoveryState.FAILED,
                null,
                result.getFailureReason(),
                result.getResolvedAt()
        );
    }

    private LedgerRecoveryResponse
    lookupLegacyLedgerEntries(
            UUID paymentId
    ) {

        List<LedgerEntry> entries =
                ledgerRepository
                        .findByPaymentId(
                                paymentId
                        );

        if (entries.isEmpty()) {

            return new LedgerRecoveryResponse(
                    paymentId,
                    LedgerRecoveryState.NOT_FOUND,
                    null,
                    null,
                    null
            );
        }

        LedgerEntry debit =
                entries.stream()
                        .filter(
                                entry ->
                                        entry.getEntryType()
                                                ==
                                                LedgerEntryType.DEBIT
                        )
                        .findFirst()
                        .orElse(null);

        LedgerEntry credit =
                entries.stream()
                        .filter(
                                entry ->
                                        entry.getEntryType()
                                                ==
                                                LedgerEntryType.CREDIT
                        )
                        .findFirst()
                        .orElse(null);

        if (
                debit == null
                        ||
                        credit == null
        ) {

            throw new IllegalStateException(
                    "Partial ledger state detected paymentId="
                            + paymentId
            );
        }

        LocalDateTime resolvedAt =
                debit.getCreatedAt()
                        != null
                        ? debit.getCreatedAt()
                        : credit.getCreatedAt();

        return new LedgerRecoveryResponse(
                paymentId,
                LedgerRecoveryState.PROCESSED,
                debit.getId(),
                null,
                resolvedAt
        );
    }
}