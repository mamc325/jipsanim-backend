package com.jipsanim.notification.repository;

import com.jipsanim.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipientUserId(Long recipientUserId, Pageable pageable);

    Page<Notification> findByRecipientUserIdAndRead(Long recipientUserId, boolean read, Pageable pageable);

    /** 소비 멱등: 이미 발행된 이벤트인지 확인. */
    boolean existsByOutboxEventId(Long outboxEventId);

    long countByOutboxEventId(Long outboxEventId);
}
