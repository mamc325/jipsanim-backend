package com.jipsanim.outbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbox Worker 설정 (spec §2, plan 설정값). worker-enabled 는 스케줄러 @ConditionalOnProperty 에서 사용.
 */
@ConfigurationProperties(prefix = "outbox")
public record OutboxProperties(
        long workerDelayMs,        // 폴링 주기(fixedDelay)
        int batchSize,             // 선점 배치 크기(LIMIT)
        long reclaimTimeoutSeconds // PROCESSING 고착 복구 임계
) {
}
