package com.jipsanim.notification.domain;

/** 알림 유형 = Outbox event_type 과 매핑(spec §2-3). */
public enum NotificationType {
    VISIT_RESERVATION_CONFIRMED,
    VISIT_RESERVATION_CANCELLED,
    REFUND_COMPLETED,
    SETTLEMENT_PAID,
    PROPERTY_APPROVED,
    PROPERTY_REJECTED,
    WAITING_QUEUE_INVITATION_GRANTED
}
