package com.jipsanim.notification.dispatch;

import com.jipsanim.notification.domain.Notification;
import com.jipsanim.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Mock 전송: notification 저장 + 로그(실발송 인프라 없음, spec §2-2). 포트만 분리해 후에 SMTP 로 교체.
 */
@Component
public class MockNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(MockNotificationSender.class);

    private final NotificationRepository notificationRepository;

    public MockNotificationSender(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Override
    public void send(Notification notification) {
        notificationRepository.save(notification);
        log.info("[MOCK NOTIFY] userId={} type={} title={}",
                notification.getRecipientUserId(), notification.getType(), notification.getTitle());
    }
}
