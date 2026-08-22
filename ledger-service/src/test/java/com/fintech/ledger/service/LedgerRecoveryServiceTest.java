package com.fintech.ledger.service;

import com.fintech.ledger.dto.LedgerRecoveryState;
import com.fintech.ledger.entity.LedgerEntry;
import com.fintech.ledger.entity.LedgerEntryType;
import com.fintech.ledger.entity.LedgerProcessingResult;
import com.fintech.ledger.entity.LedgerProcessingResultType;
import com.fintech.ledger.repository.LedgerProcessingResultRepository;
import com.fintech.ledger.repository.LedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerRecoveryServiceTest {

    @Mock
    private LedgerProcessingResultRepository
            processingResultRepository;

    @Mock
    private LedgerRepository ledgerRepository;

    private LedgerRecoveryService recoveryService;

    @BeforeEach
    void setUp() {

        recoveryService =
                new LedgerRecoveryService(
                        processingResultRepository,
                        ledgerRepository
                );
    }

    @Test
    void returnsProcessedForSuccessfulLedgerResult() {

        UUID paymentId =
                UUID.randomUUID();

        UUID transactionId =
                UUID.randomUUID();

        LocalDateTime resolvedAt =
                LocalDateTime.now();

        when(
                processingResultRepository
                        .findById(paymentId)
        ).thenReturn(
                Optional.of(
                        LedgerProcessingResult
                                .builder()
                                .paymentId(
                                        paymentId
                                )
                                .resultType(
                                        LedgerProcessingResultType
                                                .PROCESSED
                                )
                                .ledgerTransactionId(
                                        transactionId
                                )
                                .resolvedAt(
                                        resolvedAt
                                )
                                .build()
                )
        );

        var response =
                recoveryService.lookup(
                        paymentId
                );

        assertEquals(
                LedgerRecoveryState.PROCESSED,
                response.state()
        );

        assertEquals(
                transactionId,
                response.ledgerTransactionId()
        );

        assertNull(
                response.failureReason()
        );

        verifyNoInteractions(
                ledgerRepository
        );
    }

    @Test
    void returnsFailureWithOriginalBusinessReason() {

        UUID paymentId =
                UUID.randomUUID();

        when(
                processingResultRepository
                        .findById(paymentId)
        ).thenReturn(
                Optional.of(
                        LedgerProcessingResult
                                .builder()
                                .paymentId(
                                        paymentId
                                )
                                .resultType(
                                        LedgerProcessingResultType
                                                .FAILED
                                )
                                .failureReason(
                                        "INSUFFICIENT_BALANCE"
                                )
                                .resolvedAt(
                                        LocalDateTime.now()
                                )
                                .build()
                )
        );

        var response =
                recoveryService.lookup(
                        paymentId
                );

        assertEquals(
                LedgerRecoveryState.FAILED,
                response.state()
        );

        assertEquals(
                "INSUFFICIENT_BALANCE",
                response.failureReason()
        );
    }

    @Test
    void returnsNotFoundWhenLedgerNeverProcessedPayment() {

        UUID paymentId =
                UUID.randomUUID();

        when(
                processingResultRepository
                        .findById(paymentId)
        ).thenReturn(
                Optional.empty()
        );

        when(
                ledgerRepository
                        .findByPaymentId(paymentId)
        ).thenReturn(
                List.of()
        );

        var response =
                recoveryService.lookup(
                        paymentId
                );

        assertEquals(
                LedgerRecoveryState.NOT_FOUND,
                response.state()
        );

        assertNull(
                response.ledgerTransactionId()
        );

        assertNull(
                response.failureReason()
        );
    }

    @Test
    void legacyDoubleEntryIsRecognizedAsProcessed() {

        UUID paymentId =
                UUID.randomUUID();

        UUID debitId =
                UUID.randomUUID();

        LocalDateTime createdAt =
                LocalDateTime.now();

        LedgerEntry debit =
                LedgerEntry.builder()
                        .id(debitId)
                        .paymentId(
                                paymentId
                        )
                        .accountId(
                                UUID.randomUUID()
                        )
                        .entryType(
                                LedgerEntryType.DEBIT
                        )
                        .amount(
                                new BigDecimal(
                                        "2000.00"
                                )
                        )
                        .createdAt(
                                createdAt
                        )
                        .build();

        LedgerEntry credit =
                LedgerEntry.builder()
                        .id(UUID.randomUUID())
                        .paymentId(
                                paymentId
                        )
                        .accountId(
                                UUID.randomUUID()
                        )
                        .entryType(
                                LedgerEntryType.CREDIT
                        )
                        .amount(
                                new BigDecimal(
                                        "2000.00"
                                )
                        )
                        .createdAt(
                                createdAt
                        )
                        .build();

        when(
                processingResultRepository
                        .findById(paymentId)
        ).thenReturn(
                Optional.empty()
        );

        when(
                ledgerRepository
                        .findByPaymentId(paymentId)
        ).thenReturn(
                List.of(
                        debit,
                        credit
                )
        );

        var response =
                recoveryService.lookup(
                        paymentId
                );

        assertEquals(
                LedgerRecoveryState.PROCESSED,
                response.state()
        );

        assertEquals(
                debitId,
                response.ledgerTransactionId()
        );
    }

    @Test
    void partialLegacyLedgerStateIsRejected() {

        UUID paymentId =
                UUID.randomUUID();

        LedgerEntry debit =
                LedgerEntry.builder()
                        .id(UUID.randomUUID())
                        .paymentId(
                                paymentId
                        )
                        .entryType(
                                LedgerEntryType.DEBIT
                        )
                        .build();

        when(
                processingResultRepository
                        .findById(paymentId)
        ).thenReturn(
                Optional.empty()
        );

        when(
                ledgerRepository
                        .findByPaymentId(paymentId)
        ).thenReturn(
                List.of(debit)
        );

        assertThrows(
                IllegalStateException.class,
                () ->
                        recoveryService.lookup(
                                paymentId
                        )
        );
    }
}