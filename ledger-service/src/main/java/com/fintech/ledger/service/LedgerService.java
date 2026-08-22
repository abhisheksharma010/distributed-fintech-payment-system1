package com.fintech.ledger.service;

import com.fintech.ledger.entity.Account;
import com.fintech.ledger.entity.AccountStatus;
import com.fintech.ledger.entity.LedgerEntry;
import com.fintech.ledger.entity.LedgerEntryType;
import com.fintech.ledger.entity.LedgerProcessingResult;
import com.fintech.ledger.entity.LedgerProcessingResultType;
import com.fintech.ledger.event.LedgerCompletedEvent;
import com.fintech.ledger.event.LedgerFailedEvent;
import com.fintech.ledger.event.PaymentCreatedEvent;
import com.fintech.ledger.exception.TransferFailureReason;
import com.fintech.ledger.producer.LedgerEventProducer;
import com.fintech.ledger.repository.AccountRepository;
import com.fintech.ledger.repository.LedgerProcessingResultRepository;
import com.fintech.ledger.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fintech.ledger.config.LedgerCacheNames;
import com.fintech.ledger.dto.LedgerEntryResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerService {


    private final LedgerCacheService
            ledgerCacheService;

    private final LedgerCacheMetrics
            ledgerCacheMetrics;
    private final LedgerRepository
            ledgerRepository;

    private final AccountRepository
            accountRepository;

    private final LedgerEventProducer
            ledgerEventProducer;

    private final LedgerProcessingResultRepository
            processingResultRepository;

    @Cacheable(
            cacheNames =
                    LedgerCacheNames.ALL_ENTRIES,
            key = "'all'"
    )
    @Transactional(readOnly = true)
    public List<LedgerEntryResponse>
    getAllEntries() {

        ledgerCacheMetrics.recordMiss();

        log.info(
                "Ledger cache miss cache={} key=all action=DATABASE_READ",
                LedgerCacheNames.ALL_ENTRIES
        );

        return ledgerRepository
                .findAll()
                .stream()
                .map(
                        this::toResponse
                )
                .toList();
    }

    @Cacheable(
            cacheNames =
                    LedgerCacheNames.USER_ENTRIES,
            key = "#userId"
    )
    @Transactional(readOnly = true)
    public List<LedgerEntryResponse>
    getEntriesForUser(
            UUID userId
    ) {

        ledgerCacheMetrics.recordMiss();

        log.info(
                "Ledger cache miss cache={} action=DATABASE_READ",
                LedgerCacheNames.USER_ENTRIES
        );

        return ledgerRepository
                .findByOwnerId(
                        userId
                )
                .stream()
                .map(
                        this::toResponse
                )
                .toList();
    }

    private LedgerEntryResponse toResponse(
            LedgerEntry entry
    ) {

        return new LedgerEntryResponse(
                entry.getId(),
                entry.getAccountId(),
                entry.getPaymentId(),
                entry.getEntryType(),
                entry.getAmount(),
                entry.getCreatedAt()
        );
    }

    @Transactional
    public void processTransfer(
            PaymentCreatedEvent event
    ) {

        UUID paymentId =
                event == null
                        ? null
                        : event.getPaymentId();

        log.info(
                "LedgerService started processing paymentId={}",
                paymentId
        );

        try {

            validatePayment(event);

            Optional<LedgerProcessingResult>
                    existingResult =
                    processingResultRepository
                            .findById(
                                    paymentId
                            );

            if (existingResult.isPresent()) {

                republishExistingResult(
                        existingResult.get()
                );

                return;
            }

            TransferResult result =
                    executeTransfer(event);

            saveProcessedResult(
                    paymentId,
                    result.ledgerTransactionId()
            );

            if (result.newlyProcessed()) {

                evictLedgerCachesAfterCommit(
                        event.getSenderId(),
                        event.getReceiverId()
                );
            }

            publishLedgerCompletedAfterCommit(
                    paymentId,
                    result.ledgerTransactionId()
            );

            log.info(
                    "Ledger processing completed paymentId={} newlyProcessed={} ledgerTransactionId={}",
                    paymentId,
                    result.newlyProcessed(),
                    result.ledgerTransactionId()
            );

        } catch (
                TransferFailureException ex
        ) {

            log.warn(
                    "Transfer rejected paymentId={} reason={}",
                    paymentId,
                    ex.getReason()
            );

            if (paymentId != null) {

                LedgerProcessingResult result =
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
                                        ex.getReason()
                                                .name()
                                )
                                .resolvedAt(
                                        LocalDateTime.now()
                                )
                                .build();

                processingResultRepository
                        .save(result);

                publishLedgerFailedAfterCommit(
                        paymentId,
                        ex.getReason()
                                .name()
                );

            } else {

                ledgerEventProducer
                        .sendLedgerFailed(
                                LedgerFailedEvent.builder()
                                        .paymentId(null)
                                        .reason(
                                                ex.getReason()
                                                        .name()
                                        )
                                        .failedAt(
                                                Instant.now()
                                        )
                                        .build()
                        );
            }

        } catch (RuntimeException ex) {

            log.error(
                    "Ledger transfer failed due to infrastructure error paymentId={}",
                    paymentId,
                    ex
            );

            throw ex;
        }
    }

    private void evictLedgerCachesAfterCommit(
            UUID senderId,
            UUID receiverId
    ) {

        runAfterCommit(
                () ->
                        ledgerCacheService
                                .evictAfterTransfer(
                                        senderId,
                                        receiverId
                                )
        );
    }

    private TransferResult executeTransfer(
            PaymentCreatedEvent event
    ) {

        UUID senderOwnerId =
                event.getSenderId();

        UUID receiverOwnerId =
                event.getReceiverId();

        Account sender;
        Account receiver;

        if (
                senderOwnerId
                        .compareTo(
                                receiverOwnerId
                        ) < 0
        ) {

            sender =
                    findAndLockAccount(
                            senderOwnerId
                    );

            receiver =
                    findAndLockAccount(
                            receiverOwnerId
                    );

        } else {

            receiver =
                    findAndLockAccount(
                            receiverOwnerId
                    );

            sender =
                    findAndLockAccount(
                            senderOwnerId
                    );
        }

        /*
         * Recheck after account locking because another
         * concurrent consumer may have resolved this payment
         * while this transaction was waiting for the rows.
         */
        Optional<LedgerProcessingResult>
                concurrentResult =
                processingResultRepository
                        .findById(
                                event.getPaymentId()
                        );

        if (concurrentResult.isPresent()) {

            LedgerProcessingResult result =
                    concurrentResult.get();

            if (
                    result.getResultType()
                            ==
                            LedgerProcessingResultType
                                    .FAILED
            ) {

                throw new ExistingBusinessFailureException(
                        result
                );
            }

            return new TransferResult(
                    false,
                    result.getLedgerTransactionId()
            );
        }

        /*
         * Backward compatibility for successful transfers
         * created before ledger_processing_results existed.
         */
        if (
                ledgerRepository
                        .existsByPaymentId(
                                event.getPaymentId()
                        )
        ) {

            UUID transactionId =
                    existingLedgerTransactionId(
                            event.getPaymentId()
                    );

            return new TransferResult(
                    false,
                    transactionId
            );
        }

        validateAccounts(
                sender,
                receiver,
                event
        );

        validateBalances(
                sender,
                receiver,
                event.getAmount()
        );

        applyBalanceChanges(
                sender,
                receiver,
                event.getAmount()
        );

        accountRepository.save(sender);
        accountRepository.save(receiver);

        UUID ledgerTransactionId =
                createDoubleEntry(
                        event,
                        sender,
                        receiver
                );

        return new TransferResult(
                true,
                ledgerTransactionId
        );
    }

    private void saveProcessedResult(
            UUID paymentId,
            UUID ledgerTransactionId
    ) {

        if (
                processingResultRepository
                        .existsById(
                                paymentId
                        )
        ) {
            return;
        }

        processingResultRepository.save(
                LedgerProcessingResult.builder()
                        .paymentId(
                                paymentId
                        )
                        .resultType(
                                LedgerProcessingResultType
                                        .PROCESSED
                        )
                        .ledgerTransactionId(
                                ledgerTransactionId
                        )
                        .resolvedAt(
                                LocalDateTime.now()
                        )
                        .build()
        );
    }

    private void republishExistingResult(
            LedgerProcessingResult result
    ) {

        if (
                result.getResultType()
                        ==
                        LedgerProcessingResultType
                                .PROCESSED
        ) {

            publishLedgerCompletedAfterCommit(
                    result.getPaymentId(),
                    result.getLedgerTransactionId()
            );

            return;
        }

        publishLedgerFailedAfterCommit(
                result.getPaymentId(),
                result.getFailureReason()
        );
    }

    private Account findAndLockAccount(
            UUID ownerId
    ) {

        return accountRepository
                .findByOwnerIdForUpdate(
                        ownerId
                )
                .orElseThrow(
                        () ->
                                new TransferFailureException(
                                        TransferFailureReason
                                                .ACCOUNT_NOT_FOUND
                                )
                );
    }

    private void validateAccounts(
            Account sender,
            Account receiver,
            PaymentCreatedEvent event
    ) {

        if (
                sender.getStatus()
                        != AccountStatus.ACTIVE
                        ||
                        receiver.getStatus()
                                != AccountStatus.ACTIVE
        ) {

            throw new TransferFailureException(
                    TransferFailureReason
                            .ACCOUNT_INACTIVE
            );
        }

        if (
                !"INR".equalsIgnoreCase(
                        event.getCurrency()
                )
                        ||
                        !event.getCurrency()
                                .equalsIgnoreCase(
                                        sender.getCurrency()
                                )
                        ||
                        !event.getCurrency()
                                .equalsIgnoreCase(
                                        receiver.getCurrency()
                                )
        ) {

            throw new TransferFailureException(
                    TransferFailureReason
                            .CURRENCY_MISMATCH
            );
        }
    }

    private void validateBalances(
            Account sender,
            Account receiver,
            BigDecimal amount
    ) {

        if (
                sender.getBalance() == null
                        ||
                        receiver.getBalance() == null
        ) {

            throw new TransferFailureException(
                    TransferFailureReason
                            .INVALID_TRANSFER
            );
        }

        if (
                sender.getBalance()
                        .compareTo(
                                amount
                        ) < 0
        ) {

            throw new TransferFailureException(
                    TransferFailureReason
                            .INSUFFICIENT_BALANCE
            );
        }
    }

    private void applyBalanceChanges(
            Account sender,
            Account receiver,
            BigDecimal amount
    ) {

        sender.setBalance(
                sender.getBalance()
                        .subtract(amount)
        );

        receiver.setBalance(
                receiver.getBalance()
                        .add(amount)
        );
    }

    private UUID createDoubleEntry(
            PaymentCreatedEvent event,
            Account sender,
            Account receiver
    ) {

        LocalDateTime now =
                LocalDateTime.now();

        LedgerEntry debit =
                LedgerEntry.builder()
                        .accountId(
                                sender.getId()
                        )
                        .paymentId(
                                event.getPaymentId()
                        )
                        .entryType(
                                LedgerEntryType.DEBIT
                        )
                        .amount(
                                event.getAmount()
                        )
                        .createdAt(now)
                        .build();

        LedgerEntry credit =
                LedgerEntry.builder()
                        .accountId(
                                receiver.getId()
                        )
                        .paymentId(
                                event.getPaymentId()
                        )
                        .entryType(
                                LedgerEntryType.CREDIT
                        )
                        .amount(
                                event.getAmount()
                        )
                        .createdAt(now)
                        .build();

        ledgerRepository.saveAll(
                List.of(
                        debit,
                        credit
                )
        );

        ledgerRepository.flush();

        return debit.getId();
    }

    private UUID existingLedgerTransactionId(
            UUID paymentId
    ) {

        return ledgerRepository
                .findByPaymentId(
                        paymentId
                )
                .stream()
                .filter(
                        entry ->
                                entry.getEntryType()
                                        ==
                                        LedgerEntryType.DEBIT
                )
                .map(
                        LedgerEntry::getId
                )
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Ledger entries missing for processed paymentId="
                                                + paymentId
                                )
                );
    }

    private void publishLedgerCompletedAfterCommit(
            UUID paymentId,
            UUID ledgerTransactionId
    ) {

        LedgerCompletedEvent event =
                LedgerCompletedEvent.builder()
                        .paymentId(
                                paymentId
                        )
                        .ledgerTransactionId(
                                ledgerTransactionId
                        )
                        .completedAt(
                                Instant.now()
                        )
                        .build();

        runAfterCommit(
                () ->
                        ledgerEventProducer
                                .sendLedgerCompleted(
                                        event
                                )
        );
    }

    private void publishLedgerFailedAfterCommit(
            UUID paymentId,
            String reason
    ) {

        LedgerFailedEvent event =
                LedgerFailedEvent.builder()
                        .paymentId(
                                paymentId
                        )
                        .reason(reason)
                        .failedAt(
                                Instant.now()
                        )
                        .build();

        runAfterCommit(
                () ->
                        ledgerEventProducer
                                .sendLedgerFailed(
                                        event
                                )
        );
    }

    private void runAfterCommit(
            Runnable action
    ) {

        if (
                !TransactionSynchronizationManager
                        .isSynchronizationActive()
        ) {

            action.run();
            return;
        }

        TransactionSynchronizationManager
                .registerSynchronization(
                        new TransactionSynchronization() {

                            @Override
                            public void afterCommit() {
                                action.run();
                            }
                        }
                );
    }

    private void validatePayment(
            PaymentCreatedEvent event
    ) {

        if (
                event == null
                        ||
                        event.getPaymentId() == null
                        ||
                        event.getSenderId() == null
                        ||
                        event.getReceiverId() == null
                        ||
                        event.getSenderId()
                                .equals(
                                        event.getReceiverId()
                                )
                        ||
                        event.getAmount() == null
                        ||
                        event.getAmount()
                                .signum() <= 0
                        ||
                        event.getAmount()
                                .scale() > 2
                        ||
                        event.getAmount()
                                .precision() > 14
                        ||
                        event.getCurrency() == null
                        ||
                        event.getCurrency()
                                .isBlank()
        ) {

            throw new TransferFailureException(
                    TransferFailureReason
                            .INVALID_TRANSFER
            );
        }
    }

    private record TransferResult(
            boolean newlyProcessed,
            UUID ledgerTransactionId
    ) {
    }

    private static class
    ExistingBusinessFailureException
            extends RuntimeException {

        private final LedgerProcessingResult result;

        private ExistingBusinessFailureException(
                LedgerProcessingResult result
        ) {
            this.result = result;
        }
    }

    private static class TransferFailureException
            extends RuntimeException {

        private final TransferFailureReason reason;

        private TransferFailureException(
                TransferFailureReason reason
        ) {

            super(reason.name());

            this.reason = reason;
        }

        private TransferFailureReason
        getReason() {
            return reason;
        }
    }
}