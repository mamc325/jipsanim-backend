package com.jipsanim.outbox;

import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.notification.dispatch.NotificationSender;
import com.jipsanim.notification.repository.NotificationRepository;
import com.jipsanim.outbox.domain.OutboxEvent;
import com.jipsanim.outbox.domain.OutboxStatus;
import com.jipsanim.outbox.repository.OutboxEventRepository;
import com.jipsanim.outbox.worker.OutboxPoller;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4차 Phase 3: 발행 실패 주입 → 재시도 스케줄(attempts++/PENDING/백오프), 알림 미생성.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxWorkerFailureTest {

    @Autowired
    OutboxPoller poller;
    @Autowired
    OutboxEventRepository outboxRepository;
    @Autowired
    NotificationRepository notificationRepository;

    @MockBean
    NotificationSender notificationSender; // send() 가 예외를 던지도록 주입

    private static final AtomicLong SEQ = new AtomicLong(60_000);

    @Test
    @DisplayName("발행 실패 → attempts=1, PENDING(백오프), 알림 0")
    void failureReschedules() {
        BDDMockito.willThrow(new RuntimeException("sender down"))
                .given(notificationSender).send(BDDMockito.any());

        long n = SEQ.incrementAndGet();
        OutboxEvent event = outboxRepository.saveAndFlush(OutboxEvent.create(
                "RESERVATION", n, "VISIT_RESERVATION_CONFIRMED", "VISIT_RESERVATION_CONFIRMED:" + n,
                "{\"recipientUserId\":999}", LocalDateTime.now().minusMinutes(1)));

        poller.pollOnce();

        OutboxEvent reloaded = outboxRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getNextRetryAt()).isAfter(LocalDateTime.now()); // 백오프로 미래
        assertThat(notificationRepository.countByOutboxEventId(event.getId())).isZero();
    }
}
