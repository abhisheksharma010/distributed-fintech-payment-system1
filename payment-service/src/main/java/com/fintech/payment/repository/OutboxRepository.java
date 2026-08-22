package com.fintech.payment.repository;

import com.fintech.payment.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxRepository
        extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findByPublishedFalse();

    long countByPublishedFalse();

    List<OutboxEvent> findByAggregateId(
            UUID aggregateId
    );

    Optional<OutboxEvent>
    findFirstByAggregateIdAndEventTypeOrderByCreatedAtAsc(
            UUID aggregateId,
            String eventType
    );

    @Query(
            value = """
                    SELECT *
                    FROM outbox_events
                    WHERE published = FALSE
                    ORDER BY created_at ASC
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                    """,
            nativeQuery = true
    )
    List<OutboxEvent> findPendingForUpdate(
            @Param("batchSize")
            int batchSize
    );
}