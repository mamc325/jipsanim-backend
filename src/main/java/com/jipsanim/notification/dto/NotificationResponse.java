package com.jipsanim.notification.dto;

import com.jipsanim.notification.domain.Notification;
import com.jipsanim.notification.domain.NotificationType;

import java.time.LocalDateTime;

public record NotificationResponse(
        Long notificationId,
        NotificationType type,
        String title,
        String message,
        boolean isRead,
        LocalDateTime createdAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getMessage(),
                n.isRead(), n.getCreatedAt());
    }
}
