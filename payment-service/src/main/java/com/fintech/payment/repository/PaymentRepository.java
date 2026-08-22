package com.fintech.payment.repository;

import com.fintech.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository
        extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(
            String idempotencyKey
    );

    long countByIdempotencyKey(
            String idempotencyKey
    );

    Page<Payment> findBySenderIdOrReceiverId(
            UUID senderId,
            UUID receiverId,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p
            FROM Payment p
            WHERE p.id = :paymentId
            """)
    Optional<Payment> findByIdForUpdate(
            @Param("paymentId")
            UUID paymentId
    );

    @Query(
            value = """
                    SELECT *
                    FROM payments
                    WHERE status = 'PROCESSING'
                      AND updated_at < :processingCutoff
                      AND (
                            last_recovery_at IS NULL
                            OR last_recovery_at < :retryCutoff
                      )
                    ORDER BY updated_at ASC
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                    """,
            nativeQuery = true
    )
    List<Payment> findStuckPaymentsForRecovery(
            @Param("processingCutoff")
            LocalDateTime processingCutoff,

            @Param("retryCutoff")
            LocalDateTime retryCutoff,

            @Param("batchSize")
            int batchSize
    );
}