package com.jipsanim.reservation.queue;

import com.jipsanim.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WaitingQueueIntegrationTest {

    @Autowired
    WaitingQueueService queue;

    @Test
    @DisplayName("진입/순번/중복진입 방지 + FIFO")
    void enqueueAndRank() {
        long slot = 1001L;
        assertThat(queue.enqueue(slot, 11L)).isTrue();
        assertThat(queue.enqueue(slot, 22L)).isTrue();
        assertThat(queue.enqueue(slot, 11L)).isFalse(); // 중복 진입

        assertThat(queue.rank(slot, 11L)).isEqualTo(1);
        assertThat(queue.rank(slot, 22L)).isEqualTo(2);
        assertThat(queue.activeSlots()).contains(slot);
    }

    @Test
    @DisplayName("예약권 발급: 선두 1명, 두 번째 발급은 토큰 있어 무발급")
    void tryIssue() {
        long slot = 1002L;
        queue.enqueue(slot, 11L);
        queue.enqueue(slot, 22L);

        assertThat(queue.tryIssue(slot)).isEqualTo(11L);
        assertThat(queue.hasToken(slot, 11L)).isTrue();
        assertThat(queue.rank(slot, 11L)).isNull();      // 발급되며 큐에서 빠짐
        assertThat(queue.tokenTtlSeconds(slot)).isGreaterThan(0);
        assertThat(queue.tryIssue(slot)).isNull();       // 토큰 존재 → 무발급
    }

    @Test
    @DisplayName("releaseTokenIfOwner: 소유자만 삭제, 이후 다음 대기자 발급")
    void releaseTokenIfOwner() {
        long slot = 1003L;
        queue.enqueue(slot, 11L);
        queue.enqueue(slot, 22L);
        queue.tryIssue(slot); // 11L 발급

        queue.releaseTokenIfOwner(slot, 22L);            // 비소유자 → 무효
        assertThat(queue.hasToken(slot, 11L)).isTrue();

        queue.releaseTokenIfOwner(slot, 11L);            // 소유자 → 삭제
        assertThat(queue.tokenOwner(slot)).isNull();

        assertThat(queue.tryIssue(slot)).isEqualTo(22L); // 다음 대기자
    }

    @Test
    @DisplayName("cleanupSlot: 토큰/큐/active-set 전체 삭제")
    void cleanupSlot() {
        long slot = 1004L;
        queue.enqueue(slot, 11L);
        queue.tryIssue(slot);

        queue.cleanupSlot(slot);

        assertThat(queue.tokenOwner(slot)).isNull();
        assertThat(queue.isQueueEmpty(slot)).isTrue();
        assertThat(queue.activeSlots()).doesNotContain(slot);
    }

    @Test
    @DisplayName("동시 발급: 30 스레드가 tryIssue 해도 슬롯당 1명만 발급")
    void concurrentIssueSingleToken() throws Exception {
        long slot = 1005L;
        for (long u = 1; u <= 30; u++) {
            queue.enqueue(slot, u);
        }
        ExecutorService pool = Executors.newFixedThreadPool(30);
        List<Callable<Long>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tasks.add(() -> queue.tryIssue(slot));
        }
        List<Future<Long>> results = pool.invokeAll(tasks);
        pool.shutdown();

        long issued = 0;
        for (Future<Long> f : results) {
            if (f.get() != null) {
                issued++;
            }
        }
        assertThat(issued).isEqualTo(1); // active token 슬롯당 1개
    }
}
