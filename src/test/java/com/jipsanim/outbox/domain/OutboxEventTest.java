package com.jipsanim.outbox.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4차 Phase 1: OutboxEvent 상태전이 + 백오프(constitution VIII).
 */
class OutboxEventTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 14, 10, 0);

    private OutboxEvent pending() {
        return OutboxEvent.create("RESERVATION", 11L, "VISIT_RESERVATION_CONFIRMED",
                "VISIT_RESERVATION_CONFIRMED:11", "{}", NOW);
    }

    @Test
    @DisplayName("생성 → PENDING, attempts 0, next_retry_at=now")
    void created() {
        OutboxEvent e = pending();
        assertThat(e.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(e.getAttempts()).isZero();
        assertThat(e.getNextRetryAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("선점 → PROCESSING, 발행 성공 → PUBLISHED")
    void processThenPublish() {
        OutboxEvent e = pending();
        e.markProcessing(NOW);
        assertThat(e.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(e.getProcessingStartedAt()).isEqualTo(NOW);

        e.markPublished(NOW.plusSeconds(1));
        assertThat(e.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(e.getPublishedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    @DisplayName("실패 1~5회 → PENDING + 지수 백오프(1m,5m,15m,1h,6h)")
    void failuresBackoff() {
        OutboxEvent e = pending();
        Duration[] expected = {
                Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
                Duration.ofHours(1), Duration.ofHours(6)
        };
        for (int i = 0; i < 5; i++) {
            e.markFailed(NOW, "boom");
            assertThat(e.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(e.getAttempts()).isEqualTo(i + 1);
            assertThat(e.getNextRetryAt()).isEqualTo(NOW.plus(expected[i]));
            assertThat(e.getProcessingStartedAt()).isNull();
        }
    }

    @Test
    @DisplayName("6회째 실패 → DEAD")
    void deadAfterSixth() {
        OutboxEvent e = pending();
        for (int i = 0; i < 5; i++) {
            e.markFailed(NOW, "boom");
        }
        assertThat(e.getStatus()).isEqualTo(OutboxStatus.PENDING);

        e.markFailed(NOW, "boom"); // 6회째
        assertThat(e.getAttempts()).isEqualTo(6);
        assertThat(e.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(e.isDead()).isTrue();
    }

    @Test
    @DisplayName("DEAD 재처리 reset → PENDING, attempts 0, next_retry_at=now")
    void resetFromDead() {
        OutboxEvent e = pending();
        for (int i = 0; i < 6; i++) {
            e.markFailed(NOW, "boom");
        }
        assertThat(e.isDead()).isTrue();

        LocalDateTime later = NOW.plusHours(10);
        e.reset(later);
        assertThat(e.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(e.getAttempts()).isZero();
        assertThat(e.getNextRetryAt()).isEqualTo(later);
        assertThat(e.getLastError()).isNull();
    }

    @Test
    @DisplayName("RetryPolicy: attempts>=6 이면 소진(DEAD)")
    void retryPolicyExhausted() {
        assertThat(RetryPolicy.isExhausted(5)).isFalse();
        assertThat(RetryPolicy.isExhausted(6)).isTrue();
        assertThat(RetryPolicy.backoff(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(RetryPolicy.backoff(5)).isEqualTo(Duration.ofHours(6));
    }
}
