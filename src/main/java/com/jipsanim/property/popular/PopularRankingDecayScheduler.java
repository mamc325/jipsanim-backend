package com.jipsanim.property.popular;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 트렌딩 랭킹 일 감쇠 실행(6차). 기본 매일 04:00. 테스트는 popular.decay-enabled=false 로 비활성 —
 * {@link PopularRankingDecay#decay()} 를 직접 호출해 검증.
 */
@Component
@ConditionalOnProperty(name = "popular.decay-enabled", havingValue = "true", matchIfMissing = true)
public class PopularRankingDecayScheduler {

    private static final Logger log = LoggerFactory.getLogger(PopularRankingDecayScheduler.class);

    private final PopularRankingDecay decay;

    public PopularRankingDecayScheduler(PopularRankingDecay decay) {
        this.decay = decay;
    }

    @Scheduled(cron = "${popular.decay-cron:0 0 4 * * *}")
    public void run() {
        try {
            decay.decay();
        } catch (RuntimeException e) {
            log.warn("인기 랭킹 감쇠 실패: {}", e.getMessage());
        }
    }
}
