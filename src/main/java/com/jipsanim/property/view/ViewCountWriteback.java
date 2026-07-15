package com.jipsanim.property.view;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 조회수 writeback(6차 P1). Redis 에 모인 델타를 주기적으로 DB `view_count` 에 반영.
 *
 * <p>원자 배출(원칙 II): {@code view:pending} 을 {@code view:flushing} 으로 RENAME 해 스냅샷을 떼어낸다.
 * 배출 이후 유입 증가분은 새 {@code view:pending} 으로 쌓여 <b>유실 0</b>. RENAME 은 source 부재 시 에러라
 * EXISTS 로 선확인한다. 직전 주기 실패로 {@code view:flushing} 이 남아 있으면 그것부터 처리.
 *
 * <p><b>단일 app instance 전제</b>(6차 범위): 다중 인스턴스면 RENAME/DEL 경쟁 → 분산 lock 필요(후순위).
 * 크래시 창(반영 커밋~DEL)은 근사 카운터로 허용.
 *
 * <p>스케줄링은 {@link ViewCountWritebackScheduler}(게이팅)가 담당. 이 빈은 항상 존재하며 테스트는
 * {@link #flush()} 를 직접 호출한다.
 */
@Component
public class ViewCountWriteback {

    private final StringRedisTemplate redis;
    private final ViewCountWritebackStore store;

    public ViewCountWriteback(StringRedisTemplate redis, ViewCountWritebackStore store) {
        this.redis = redis;
        this.store = store;
    }

    /** 원자 배출 → 단일 트랜잭션 반영 → 커밋 성공 후 DEL. */
    public void flush() {
        if (!Boolean.TRUE.equals(redis.hasKey(ViewCountRedisConfig.VIEW_FLUSHING))) {
            // 잔존 flushing 없음 → pending 을 flushing 으로 원자 배출
            if (!Boolean.TRUE.equals(redis.hasKey(ViewCountRedisConfig.VIEW_PENDING))) {
                return; // 처리할 것 없음
            }
            redis.rename(ViewCountRedisConfig.VIEW_PENDING, ViewCountRedisConfig.VIEW_FLUSHING);
        }
        Map<Object, Object> deltas = redis.opsForHash().entries(ViewCountRedisConfig.VIEW_FLUSHING);
        if (!deltas.isEmpty()) {
            store.applyDeltas(deltas); // 단일 트랜잭션(별도 빈)
        }
        redis.delete(ViewCountRedisConfig.VIEW_FLUSHING); // 커밋 성공 후에만
    }
}
