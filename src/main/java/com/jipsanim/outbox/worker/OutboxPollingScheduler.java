package com.jipsanim.outbox.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주기적으로 Outbox 를 폴링해 발행. 테스트에서는 outbox.worker-enabled=false 로 비활성
 * (결정적 검증을 위해 OutboxPoller.pollOnce() 를 직접 호출).
 */
@Component
@ConditionalOnProperty(name = "outbox.worker-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPollingScheduler {

    private final OutboxPoller poller;

    public OutboxPollingScheduler(OutboxPoller poller) {
        this.poller = poller;
    }

    @Scheduled(fixedDelayString = "${outbox.worker-delay-ms:2000}")
    public void run() {
        poller.pollOnce();
    }
}
