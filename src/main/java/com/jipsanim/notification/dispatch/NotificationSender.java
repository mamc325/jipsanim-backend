package com.jipsanim.notification.dispatch;

import com.jipsanim.notification.domain.Notification;

/**
 * 알림 전송 포트. 4차는 MockNotificationSender(DB 저장+로그). 후에 SMTP/푸시 어댑터로 교체 가능.
 */
public interface NotificationSender {
    void send(Notification notification);
}
