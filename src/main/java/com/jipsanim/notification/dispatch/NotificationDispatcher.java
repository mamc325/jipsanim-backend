package com.jipsanim.notification.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipsanim.notification.domain.Notification;
import com.jipsanim.notification.domain.NotificationType;
import com.jipsanim.notification.repository.NotificationRepository;
import com.jipsanim.outbox.domain.OutboxEvent;
import org.springframework.stereotype.Component;

/**
 * OutboxEvent → 알림 렌더링/전송. 소비 멱등: outbox_event_id 로 이미 발행됐으면 skip
 * (notification.outbox_event_id UNIQUE 가 최종 방어).
 */
@Component
public class NotificationDispatcher {

    private final NotificationRepository notificationRepository;
    private final NotificationSender sender;
    private final ObjectMapper objectMapper;

    public NotificationDispatcher(NotificationRepository notificationRepository,
                                  NotificationSender sender, ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.sender = sender;
        this.objectMapper = objectMapper;
    }

    /** 이벤트를 알림으로 발행. 이미 발행된 이벤트면 아무것도 하지 않음(멱등). */
    public void dispatch(OutboxEvent event) {
        if (notificationRepository.existsByOutboxEventId(event.getId())) {
            return; // 소비 멱등: 중복 배달 흡수
        }
        NotificationType type = NotificationType.valueOf(event.getEventType());
        JsonNode payload = parse(event.getPayload());
        long recipientUserId = payload.path("recipientUserId").asLong();
        Rendered r = render(type, payload);
        sender.send(Notification.create(recipientUserId, type, r.title(), r.message(), event.getId()));
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json == null ? "{}" : json);
        } catch (Exception e) {
            throw new IllegalStateException("Outbox payload 파싱 실패", e);
        }
    }

    private Rendered render(NotificationType type, JsonNode p) {
        return switch (type) {
            case VISIT_RESERVATION_CONFIRMED -> new Rendered(
                    "예약이 확정되었습니다", "방문 예약이 확정되었습니다.");
            case VISIT_RESERVATION_CANCELLED -> new Rendered(
                    "예약이 취소되었습니다", "방문 예약이 취소되었습니다.");
            case REFUND_COMPLETED -> new Rendered(
                    "환불이 완료되었습니다", "예약 취소에 따른 환불이 완료되었습니다.");
            case SETTLEMENT_PAID -> new Rendered(
                    "정산이 지급되었습니다",
                    "%s 정산금 %d원이 지급되었습니다."
                            .formatted(p.path("settlementMonth").asText("해당 월"), p.path("payoutAmount").asLong()));
            case PROPERTY_APPROVED -> new Rendered(
                    "매물이 승인되었습니다", "등록하신 매물이 검증을 통과해 승인되었습니다.");
            case PROPERTY_REJECTED -> new Rendered(
                    "매물이 반려되었습니다",
                    "등록하신 매물이 반려되었습니다. 사유: %s".formatted(p.path("reason").asText("미기재")));
            case WAITING_QUEUE_INVITATION_GRANTED -> new Rendered(
                    "예약권이 발급되었습니다", "대기하신 방문 슬롯의 예약권이 발급되었습니다. 제한 시간 내에 예약을 완료해 주세요.");
        };
    }

    private record Rendered(String title, String message) {
    }
}
