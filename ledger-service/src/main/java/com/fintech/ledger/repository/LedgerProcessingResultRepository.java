package com.fintech.ledger.repository;

import com.fintech.ledger.entity.LedgerProcessingResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LedgerProcessingResultRepository
        extends JpaRepository<
        LedgerProcessingResult,
        UUID
        > {
}