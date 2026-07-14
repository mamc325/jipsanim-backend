package com.jipsanim.outbox;

import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.notification.domain.NotificationType;
import com.jipsanim.notification.repository.NotificationRepository;
import com.jipsanim.notification.dispatch.NotificationDispatcher;
import com.jipsanim.outbox.domain.OutboxEvent;
import com.jipsanim.outbox.publisher.OutboxEventPublisher;
import com.jipsanim.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4차 Phase 2: producer 멱등(event_key) + consumer 멱등(outbox_event_id).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class OutboxProducerDispatcherTest {

    @Autowired
    OutboxEventPublisher publisher;
    @Autowired
    NotificationDispatcher dispatcher;
    @Autowired
    OutboxEventRepository outboxRepository;
    @Autowired
    NotificationRepository notificationRepository;

    private static final AtomicLong SEQ = new AtomicLong();

    @Test
    @DisplayName("producer 멱등: 같은 event_key 로 append 2회 → 이벤트 1건")
    void producerIdempotent() {
        String key = "VISIT_RESERVATION_CONFIRMED:" + SEQ.incrementAndGet();
        Map<String, Object> payload = Map.of("recipientUserId", 10L, "reservationId", 11L);

        publisher.append("RESERVATION", 11L, "VISIT_RESERVATION_CONFIRMED", key, payload);
        publisher.append("RESERVATION", 11L, "VISIT_RESERVATION_CONFIRMED", key, payload); // 중복

        assertThat(outboxRepository.countByEventKey(key)).isEqualTo(1);
    }

    @Test
    @DisplayName("consumer 멱등: 같은 이벤트 dispatch 2회 → Notification 1건")
    void consumerIdempotent() {
        long n = SEQ.incrementAndGet();
        OutboxEvent event = outboxRepository.saveAndFlush(OutboxEvent.create(
                "SETTLEMENT", 3L, "SETTLEMENT_PAID", "SETTLEMENT_PAID:" + n,
                "{\"recipientUserId\":42,\"settlementMonth\":\"2026-07\",\"payoutAmount\":360000}",
                LocalDateTime.now()));

        dispatcher.dispatch(event);
        dispatcher.dispatch(event); // 중복 배달

        assertThat(notificationRepository.countByOutboxEventId(event.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("dispatch 렌더링: 수신자/타입/제목 매핑")
    void rendering() {
        long n = SEQ.incrementAndGet();
        OutboxEvent event = outboxRepository.saveAndFlush(OutboxEvent.create(
                "RESERVATION", 11L, "VISIT_RESERVATION_CONFIRMED", "VISIT_RESERVATION_CONFIRMED:" + n,
                "{\"recipientUserId\":77,\"reservationId\":11}", LocalDateTime.now()));

        dispatcher.dispatch(event);

        var saved = notificationRepository.findByRecipientUserId(77L,
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getType()).isEqualTo(NotificationType.VISIT_RESERVATION_CONFIRMED);
        assertThat(saved.get(0).getTitle()).isEqualTo("예약이 확정되었습니다");
    }
}
