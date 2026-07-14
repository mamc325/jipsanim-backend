package com.jipsanim.notification.domain;

import com.jipsanim.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 발행된 알림. outbox_event_id UNIQUE 로 소비 멱등(이벤트당 알림 1건).
 */
@Entity
@Table(name = "notification",
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_outbox_event", columnNames = "outbox_event_id"),
        indexes = @Index(name = "idx_notification_recipient", columnList = "recipient_user_id, is_read"))
public class Notification extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NotificationType type;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "outbox_event_id", nullable = false)
    private Long outboxEventId;

    protected Notification() {
    }

    private Notification(Long recipientUserId, NotificationType type, String title, String message, Long outboxEventId) {
        this.recipientUserId = recipientUserId;
        this.type = type;
        this.title = title;
        this.message = message;
        this.read = false;
        this.outboxEventId = outboxEventId;
    }

    public static Notification create(Long recipientUserId, NotificationType type,
                                      String title, String message, Long outboxEventId) {
        return new Notification(recipientUserId, type, title, message, outboxEventId);
    }

    public void markRead() {
        this.read = true;
    }

    public boolean isOwnedBy(Long userId) {
        return this.recipientUserId.equals(userId);
    }

    public Long getId() {
        return id;
    }

    public Long getRecipientUserId() {
        return recipientUserId;
    }

    public NotificationType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public boolean isRead() {
        return read;
    }

    public Long getOutboxEventId() {
        return outboxEventId;
    }
}
