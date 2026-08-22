package com.fintech.notification.repository;

import com.fintech.notification.entity.Notification;
import com.fintech.notification.entity.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationRepository
        extends JpaRepository<Notification, UUID> {

    boolean existsByPaymentIdAndType(
            UUID paymentId,
            NotificationType type
    );

    long countByPaymentIdAndType(
            UUID paymentId,
            NotificationType type
    );
}