package com.jipsanim.outbox.worker;

import com.jipsanim.outbox.config.OutboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 폴링 1회 오케스트레이션: ①reaper → ②선점 → ③발행/실패. OutboxWorker(프록시)를 호출해 각 단위가 개별 커밋되게 한다.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxWorker worker;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxPoller(OutboxWorker worker, OutboxProperties properties, Clock clock) {
        this.worker = worker;
        this.properties = properties;
        this.clock = clock;
    }

    public void pollOnce() {
        LocalDateTime now = LocalDateTime.now(clock);
        worker.reap(now.minusSeconds(properties.reclaimTimeoutSeconds()));

        List<Long> claimed = worker.claim(now, properties.batchSize());
        for (Long id : claimed) {
            try {
                worker.publishOne(id);
            } catch (Exception ex) {
                log.warn("outbox publish failed: id={} err={}", id, ex.getMessage());
                worker.recordFailure(id, ex.getMessage());
            }
        }
    }
}
