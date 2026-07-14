package com.jipsanim.notification.dispatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 5차 Phase 2: 알림 핸들러 라우팅 계약 — NotificationType 이벤트만 지원, 색인 이벤트는 미지원.
 */
class NotificationOutboxHandlerTest {

    private final NotificationOutboxHandler handler = new NotificationOutboxHandler(null);

    @Test
    @DisplayName("supports: NotificationType 이벤트는 true")
    void supportsNotificationTypes() {
        assertThat(handler.supports("VISIT_RESERVATION_CONFIRMED")).isTrue();
        assertThat(handler.supports("SETTLEMENT_PAID")).isTrue();
        assertThat(handler.supports("WAITING_QUEUE_INVITATION_GRANTED")).isTrue();
    }

    @Test
    @DisplayName("supports: 색인/미지정 이벤트는 false")
    void rejectsNonNotification() {
        assertThat(handler.supports("PROPERTY_INDEX")).isFalse();
        assertThat(handler.supports("PROPERTY_UNINDEX")).isFalse();
        assertThat(handler.supports("UNKNOWN")).isFalse();
    }
}
