package com.jipsanim.notification.dispatch;

import com.jipsanim.notification.domain.NotificationType;
import com.jipsanim.outbox.domain.OutboxEvent;
import com.jipsanim.outbox.worker.OutboxEventHandler;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 알림 이벤트 핸들러. event_type 이 NotificationType 이면 지원하고, 기존 NotificationDispatcher 로 위임한다.
 * (4차 알림 동작 그대로 — Worker 라우팅 일반화, 5차 리팩터)
 */
@Component
public class NotificationOutboxHandler implements OutboxEventHandler {

    private static final Set<String> NOTIFICATION_TYPES =
            Arrays.stream(NotificationType.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private final NotificationDispatcher dispatcher;

    public NotificationOutboxHandler(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public boolean supports(String eventType) {
        return NOTIFICATION_TYPES.contains(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        dispatcher.dispatch(event);
    }
}
