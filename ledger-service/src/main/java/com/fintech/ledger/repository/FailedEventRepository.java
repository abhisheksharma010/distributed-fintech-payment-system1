package com.fintech.ledger.repository;

import com.fintech.ledger.entity.FailedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FailedEventRepository extends JpaRepository<FailedEvent, UUID> {
}