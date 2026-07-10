package com.jipsanim.reservation.sweep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 예약권 sweep 백스톱. 요청이 없어도 TTL 만료 후 다음 대기자에게 발급 + 만료 PENDING 정리.
 * (테스트에서는 reservation.sweep-enabled=false 로 비활성 — 결정적 검증을 위해 sweep()을 직접 호출)
 */
@Component
@ConditionalOnProperty(name = "reservation.sweep-enabled", havingValue = "true", matchIfMissing = true)
public class TokenSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(TokenSweepScheduler.class);

    private final SweepService sweepService;

    public TokenSweepScheduler(SweepService sweepService) {
        this.sweepService = sweepService;
    }

    @Scheduled(fixedDelayString = "${reservation.sweep-interval-ms:2000}")
    public void run() {
        try {
            sweepService.sweep();
        } catch (Exception e) {
            log.warn("sweep failed: {}", e.getMessage());
        }
    }
}
