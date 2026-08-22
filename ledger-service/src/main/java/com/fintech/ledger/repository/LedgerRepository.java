package com.fintech.ledger.repository;

import com.fintech.ledger.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import com.fintech.ledger.entity.LedgerEntryType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LedgerRepository
        extends JpaRepository<LedgerEntry, UUID> {

    boolean existsByPaymentId(UUID paymentId);

    List<LedgerEntry> findByPaymentId(UUID paymentId);

    @Transactional
    @Modifying
    void deleteByAccountIdIn(Collection<UUID> accountIds);

    @Query("""
        SELECT l
        FROM LedgerEntry l
        JOIN Account a ON l.accountId = a.id
        WHERE a.ownerId = :userId
        ORDER BY l.createdAt ASC
    """)
    List<LedgerEntry> findByOwnerId(
            @Param("userId") UUID userId
    );

    @Query("""
        SELECT COALESCE(SUM(l.amount), 0)
        FROM LedgerEntry l
        WHERE l.accountId = :accountId
          AND l.entryType = :entryType
        """)
    BigDecimal sumAmountByAccountIdAndEntryType(
            @Param("accountId")
            UUID accountId,

            @Param("entryType")
            LedgerEntryType entryType
    );
}