package com.jipsanim.property.popular;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ACTIVE 이탈/진입 시 랭킹·캐시 정리(6차 P3). 도메인 커밋 후(afterCommit) best-effort 동기 처리 —
 * Outbox 미사용(색인 게이팅과 독립). 정리 실패는 짧은 TTL·감쇠·DB 필터로 자연 수렴.
 *
 * <p>정합성 보장은 조회 시 DB ACTIVE 필터(권위)이고, 여기서의 ZREM/DEL 은 랭킹/캐시 최신화(best-effort).
 */
@Component
public class PopularCacheEvictor {

    private static final Logger log = LoggerFactory.getLogger(PopularCacheEvictor.class);

    private final StringRedisTemplate redis;

    public PopularCacheEvictor(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** ACTIVE 이탈(삭제/반려): 랭킹 제거 + 상세 캐시 + 인기목록 캐시 무효화. */
    public void evictOnDeactivate(Long propertyId) {
        afterCommit(() -> {
            redis.opsForZSet().remove(PropertyCacheKeys.PROPERTY_POPULAR, String.valueOf(propertyId));
            redis.delete(PropertyCacheKeys.detailKey(propertyId));
            redis.delete(PropertyCacheKeys.POPULAR_LIST);
        });
    }

    /** ACTIVE 진입(승인) 등: 상세 캐시만 무효화(다음 조회에 최신 반영). */
    public void evictDetail(Long propertyId) {
        afterCommit(() -> redis.delete(PropertyCacheKeys.detailKey(propertyId)));
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runQuietly(action); // 트랜잭션 밖이면 즉시 실행
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runQuietly(action);
            }
        });
    }

    private void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("인기/캐시 정리 실패(best-effort): {}", e.getMessage());
        }
    }
}
