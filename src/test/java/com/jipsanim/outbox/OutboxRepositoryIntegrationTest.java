package com.jipsanim.outbox;

import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.notification.domain.Notification;
import com.jipsanim.notification.domain.NotificationType;
import com.jipsanim.notification.repository.NotificationRepository;
import com.jipsanim.outbox.domain.OutboxEvent;
import com.jipsanim.outbox.domain.OutboxStatus;
import com.jipsanim.outbox.repository.OutboxEventRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4차 Phase 1: outbox_event native 쿼리(SKIP LOCKED 폴링·reaper)와 UNIQUE 제약을 MySQL 에서 검증.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class OutboxRepositoryIntegrationTest {

    @Autowired
    OutboxEventRepository outboxRepository;
    @Autowired
    NotificationRepository notificationRepository;
    @Autowired
    EntityManager em;

    private static final AtomicLong SEQ = new AtomicLong();

    private OutboxEvent save(String key, OutboxStatus status, LocalDateTime nextRetryAt, LocalDateTime processingStartedAt) {
        OutboxEvent e = OutboxEvent.create("RESERVATION", SEQ.incrementAndGet(),
                "VISIT_RESERVATION_CONFIRMED", key, "{\"x\":1}", nextRetryAt);
        if (status == OutboxStatus.PROCESSING) {
            e.markProcessing(processingStartedAt);
        }
        return outboxRepository.saveAndFlush(e);
    }

    @Test
    @DisplayName("findClaimable: PENDING & next_retry_at<=now 만 반환(미래·PROCESSING 제외)")
    void findClaimable() {
        LocalDateTime now = LocalDateTime.now();
        OutboxEvent due = save("k-due-" + SEQ.get(), OutboxStatus.PENDING, now.minusMinutes(1), null);
        save("k-future-" + SEQ.get(), OutboxStatus.PENDING, now.plusHours(1), null);
        save("k-proc-" + SEQ.get(), OutboxStatus.PROCESSING, now.minusMinutes(1), now);
        em.flush();

        List<OutboxEvent> claimable = outboxRepository.findClaimable(now, 10);

        assertThat(claimable).extracting(OutboxEvent::getId).contains(due.getId());
        assertThat(claimable).allMatch(e -> e.getStatus() == OutboxStatus.PENDING
                && !e.getNextRetryAt().isAfter(now));
    }

    @Test
    @DisplayName("reclaimStuck: 타임아웃 지난 PROCESSING → PENDING")
    void reclaimStuck() {
        LocalDateTime now = LocalDateTime.now();
        OutboxEvent stuck = save("k-stuck-" + SEQ.get(), OutboxStatus.PROCESSING, now, now.minusMinutes(10));
        em.flush();

        int reclaimed = outboxRepository.reclaimStuck(now.minusMinutes(5));
        em.clear();

        assertThat(reclaimed).isGreaterThanOrEqualTo(1);
        assertThat(outboxRepository.findById(stuck.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("event_key UNIQUE: 같은 키 2건 → 제약 위반")
    void eventKeyUnique() {
        String key = "dup-key-" + SEQ.incrementAndGet();
        save(key, OutboxStatus.PENDING, LocalDateTime.now(), null);

        assertThatThrownBy(() -> save(key, OutboxStatus.PENDING, LocalDateTime.now(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("notification.outbox_event_id UNIQUE: 같은 이벤트 알림 2건 → 제약 위반")
    void notificationOutboxUnique() {
        long outboxId = SEQ.incrementAndGet();
        notificationRepository.saveAndFlush(Notification.create(
                1L, NotificationType.VISIT_RESERVATION_CONFIRMED, "제목", "본문", outboxId));

        assertThatThrownBy(() -> notificationRepository.saveAndFlush(Notification.create(
                1L, NotificationType.VISIT_RESERVATION_CONFIRMED, "제목2", "본문2", outboxId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
