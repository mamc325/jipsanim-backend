package com.jipsanim.outbox;

import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.notification.repository.NotificationRepository;
import com.jipsanim.outbox.domain.OutboxEvent;
import com.jipsanim.outbox.domain.OutboxStatus;
import com.jipsanim.outbox.repository.OutboxEventRepository;
import com.jipsanim.outbox.worker.OutboxPoller;
import com.jipsanim.outbox.worker.OutboxWorker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4차 Phase 3: Worker 폴링 발행 + reaper 복구 + DEAD (실사 sender). 결정성 위해 pollOnce() 직접 호출.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxWorkerIntegrationTest {

    @Autowired
    OutboxPoller poller;
    @Autowired
    OutboxWorker worker;
    @Autowired
    OutboxEventRepository outboxRepository;
    @Autowired
    NotificationRepository notificationRepository;

    private static final AtomicLong SEQ = new AtomicLong(50_000);

    private OutboxEvent saveEvent(long recipient, LocalDateTime nextRetryAt) {
        long n = SEQ.incrementAndGet();
        return outboxRepository.saveAndFlush(OutboxEvent.create(
                "RESERVATION", n, "VISIT_RESERVATION_CONFIRMED", "VISIT_RESERVATION_CONFIRMED:" + n,
                "{\"recipientUserId\":" + recipient + ",\"reservationId\":" + n + "}", nextRetryAt));
    }

    @Test
    @DisplayName("폴링 발행: PENDING due → PUBLISHED + Notification 생성")
    void pollPublishes() {
        long recipient = SEQ.incrementAndGet();
        OutboxEvent event = saveEvent(recipient, LocalDateTime.now().minusMinutes(1));

        poller.pollOnce();

        assertThat(outboxRepository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(notificationRepository.countByOutboxEventId(event.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("reaper: 고착된 PROCESSING → 복구 후 같은 폴링에서 발행")
    void reaperRecoversAndPublishes() {
        long recipient = SEQ.incrementAndGet();
        long n = SEQ.incrementAndGet();
        OutboxEvent stuck = OutboxEvent.create("RESERVATION", n, "VISIT_RESERVATION_CONFIRMED",
                "VISIT_RESERVATION_CONFIRMED:" + n,
                "{\"recipientUserId\":" + recipient + "}", LocalDateTime.now().minusMinutes(1));
        stuck.markProcessing(LocalDateTime.now().minusSeconds(400)); // 타임아웃(300s) 초과 고착
        OutboxEvent saved = outboxRepository.saveAndFlush(stuck);

        poller.pollOnce(); // reap → PENDING → claim → publish

        assertThat(outboxRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    @DisplayName("재시도 소진: recordFailure 6회 → DEAD")
    void deadAfterSixFailures() {
        OutboxEvent event = saveEvent(SEQ.incrementAndGet(), LocalDateTime.now());

        for (int i = 0; i < 6; i++) {
            worker.recordFailure(event.getId(), "boom");
        }

        OutboxEvent reloaded = outboxRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(reloaded.getAttempts()).isEqualTo(6);
    }
}
