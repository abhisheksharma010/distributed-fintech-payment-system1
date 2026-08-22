package com.fintech.ledger.repository;

import com.fintech.ledger.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository
        extends JpaRepository<Account, UUID> {

    Optional<Account> findByOwnerId(
            UUID ownerId
    );

    Optional<Account> findByOwnerIdAndCurrency(
            UUID ownerId,
            String currency
    );

    boolean existsByOwnerIdAndCurrency(
            UUID ownerId,
            String currency
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT a
            FROM Account a
            WHERE a.id = :id
            """)
    Optional<Account> findByIdForUpdate(
            @Param("id")
            UUID id
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT a
            FROM Account a
            WHERE a.ownerId = :ownerId
            """)
    Optional<Account> findByOwnerIdForUpdate(
            @Param("ownerId")
            UUID ownerId
    );
}