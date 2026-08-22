package com.fintech.notification.service;

import com.fintech.notification.entity.Notification;
import com.fintech.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationPersistenceService {

    private final NotificationRepository notificationRepository;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public Notification save(
            Notification notification
    ) {

        return notificationRepository
                .saveAndFlush(notification);
    }
}